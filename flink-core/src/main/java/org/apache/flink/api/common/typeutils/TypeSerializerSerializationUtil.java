/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.api.common.typeutils;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.runtime.KryoRegistrationSerializerConfigSnapshot;
import org.apache.flink.core.io.VersionedIOReadableWritable;
import org.apache.flink.core.memory.ByteArrayInputStreamWithPos;
import org.apache.flink.core.memory.ByteArrayOutputStreamWithPos;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataInputViewStreamWrapper;
import org.apache.flink.core.memory.DataOutputView;
import org.apache.flink.core.memory.DataOutputViewStreamWrapper;
import org.apache.flink.util.InstantiationUtil;
import org.apache.flink.util.Preconditions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Utility methods for serialization of {@link TypeSerializer} and {@link TypeSerializerConfigSnapshot}.
 */
@Internal
public class TypeSerializerSerializationUtil {

	private static final Logger LOG = LoggerFactory.getLogger(TypeSerializerSerializationUtil.class);

	/**
	 * This is maintained as a temporary workaround for FLINK-6869.
	 *
	 * <p>Before 1.3, the Scala serializers did not specify the serialVersionUID.
	 * Although since 1.3 they are properly specified, we still have to ignore them for now
	 * as their previous serialVersionUIDs will vary depending on the Scala version.
	 *
	 * <p>This can be removed once 1.2 is no longer supported.
	 */
	private static Set<String> scalaSerializerClassnames = new HashSet<>();
	static {
		scalaSerializerClassnames.add("org.apache.flink.api.scala.typeutils.TraversableSerializer");
		scalaSerializerClassnames.add("org.apache.flink.api.scala.typeutils.CaseClassSerializer");
		scalaSerializerClassnames.add("org.apache.flink.api.scala.typeutils.EitherSerializer");
		scalaSerializerClassnames.add("org.apache.flink.api.scala.typeutils.EnumValueSerializer");
		scalaSerializerClassnames.add("org.apache.flink.api.scala.typeutils.OptionSerializer");
		scalaSerializerClassnames.add("org.apache.flink.api.scala.typeutils.TrySerializer");
		scalaSerializerClassnames.add("org.apache.flink.api.scala.typeutils.EitherSerializer");
		scalaSerializerClassnames.add("org.apache.flink.api.scala.typeutils.UnitSerializer");
	}

	/**
	 * An {@link ObjectInputStream} that ignores serialVersionUID mismatches when deserializing objects of
	 * anonymous classes or our Scala serializer classes and also replaces occurrences of GenericData.Array
	 * (from Avro) by a dummy class so that the KryoSerializer can still be deserialized without
	 * Avro being on the classpath.
	 *
	 * <p>The {@link TypeSerializerSerializationProxy} uses this specific object input stream to read serializers,
	 * so that mismatching serialVersionUIDs of anonymous classes / Scala serializers are ignored.
	 * This is a required workaround to maintain backwards compatibility for our pre-1.3 Scala serializers.
	 * See FLINK-6869 for details.
	 *
	 * @see <a href="https://issues.apache.org/jira/browse/FLINK-6869">FLINK-6869</a>
	 */
	public static class FailureTolerantObjectInputStream extends InstantiationUtil.ClassLoaderObjectInputStream {

		public FailureTolerantObjectInputStream(InputStream in, ClassLoader cl) throws IOException {
			super(in, cl);
		}

		@Override
		protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
			ObjectStreamClass streamClassDescriptor = super.readClassDescriptor();

			try {
				Class.forName(streamClassDescriptor.getName(), false, classLoader);
			} catch (ClassNotFoundException e) {
				if (streamClassDescriptor.getName().equals("org.apache.avro.generic.GenericData$Array")) {
					ObjectStreamClass result = ObjectStreamClass.lookup(
						KryoRegistrationSerializerConfigSnapshot.DummyRegisteredClass.class);
					return result;
				}
			}

			Class localClass = resolveClass(streamClassDescriptor);
			if (scalaSerializerClassnames.contains(localClass.getName()) || localClass.isAnonymousClass()
				// isAnonymousClass does not work for anonymous Scala classes; additionally check by classname
				|| localClass.getName().contains("$anon$") || localClass.getName().contains("$anonfun")) {

				ObjectStreamClass localClassDescriptor = ObjectStreamClass.lookup(localClass);
				if (localClassDescriptor != null
					&& localClassDescriptor.getSerialVersionUID() != streamClassDescriptor.getSerialVersionUID()) {
					LOG.warn("Ignoring serialVersionUID mismatch for anonymous class {}; was {}, now {}.",
						streamClassDescriptor.getName(), streamClassDescriptor.getSerialVersionUID(), localClassDescriptor.getSerialVersionUID());

					streamClassDescriptor = localClassDescriptor;
				}
			}

			return streamClassDescriptor;
		}
	}

	/**
	 * Writes a {@link TypeSerializer} to the provided data output view.
	 *
	 * <p>It is written with a format that can be later read again using
	 * {@link #tryReadSerializer(DataInputView, ClassLoader, boolean)}.
	 *
	 * @param out the data output view.
	 * @param serializer the serializer to write.
	 *
	 * @param <T> Data type of the serializer.
	 *
	 * @throws IOException
	 */
	public static <T> void writeSerializer(DataOutputView out, TypeSerializer<T> serializer) throws IOException {
		new TypeSerializerSerializationUtil.TypeSerializerSerializationProxy<>(serializer).write(out);
	}

	/**
	 * Reads from a data input view a {@link TypeSerializer} that was previously
	 * written using {@link #writeSerializer(DataOutputView, TypeSerializer)}.
	 *
	 * <p>If deserialization fails for any reason (corrupted serializer bytes, serializer class
	 * no longer in classpath, serializer class no longer valid, etc.), {@code null} will
	 * be returned instead.
	 *
	 * @param in the data input view.
	 * @param userCodeClassLoader the user code class loader to use.
	 *
	 * @param <T> Data type of the serializer.
	 *
	 * @return the deserialized serializer.
	 */
	public static <T> TypeSerializer<T> tryReadSerializer(DataInputView in, ClassLoader userCodeClassLoader) {
		return tryReadSerializer(in, userCodeClassLoader, false);
	}

	/**
	 * Reads from a data input view a {@link TypeSerializer} that was previously
	 * written using {@link #writeSerializer(DataOutputView, TypeSerializer)}.
	 *
	 * <p>If deserialization fails due to {@link ClassNotFoundException} or {@link InvalidClassException},
	 * users can opt to use a dummy {@link UnloadableDummyTypeSerializer} to hold the serializer bytes,
	 * otherwise {@code null} is returned. If the failure is due to a {@link java.io.StreamCorruptedException},
	 * then {@code null} is returned.
	 *
	 * @param in the data input view.
	 * @param userCodeClassLoader the user code class loader to use.
	 * @param useDummyPlaceholder whether or not to use a dummy {@link UnloadableDummyTypeSerializer} to hold the
	 *                            serializer bytes in the case of a {@link ClassNotFoundException} or
	 *                            {@link InvalidClassException}.
	 *
	 * @param <T> Data type of the serializer.
	 *
	 * @return the deserialized serializer.
	 */
	public static <T> TypeSerializer<T> tryReadSerializer(DataInputView in, ClassLoader userCodeClassLoader, boolean useDummyPlaceholder) {
		final TypeSerializerSerializationUtil.TypeSerializerSerializationProxy<T> proxy =
			new TypeSerializerSerializationUtil.TypeSerializerSerializationProxy<>(userCodeClassLoader, useDummyPlaceholder);

		try {
			proxy.read(in);
			return proxy.getTypeSerializer();
		} catch (IOException e) {
			LOG.warn("Deserialization of serializer errored; replacing with null.", e);

			return null;
		}
	}

	/**
	 * Write a list of serializers and their corresponding config snapshots to the provided
	 * data output view. This method writes in a fault tolerant way, so that when read again
	 * using {@link #readSerializersAndConfigsWithResilience(DataInputView, ClassLoader)}, if
	 * deserialization of the serializer fails, its configuration snapshot will remain intact.
	 *
	 * <p>Specifically, all written serializers and their config snapshots are indexed by their
	 * offset positions within the serialized bytes. The serialization format is as follows:
	 * <ul>
	 *     <li>1. number of serializer and configuration snapshot pairs.</li>
	 *     <li>2. offsets of each serializer and configuration snapshot, in order.</li>
	 *     <li>3. total number of bytes for the serialized serializers and the config snapshots.</li>
	 *     <li>4. serialized serializers and the config snapshots.</li>
	 * </ul>
	 *
	 * @param out the data output view.
	 * @param serializersAndConfigs serializer and configuration snapshot pairs
	 *
	 * @throws IOException
	 */
	public static void writeSerializersAndConfigsWithResilience(
			DataOutputView out,
			List<Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>> serializersAndConfigs) throws IOException {

		try (
			ByteArrayOutputStreamWithPos bufferWithPos = new ByteArrayOutputStreamWithPos();
			DataOutputViewStreamWrapper bufferWrapper = new DataOutputViewStreamWrapper(bufferWithPos)) {

			out.writeInt(serializersAndConfigs.size());
			for (Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot> serAndConfSnapshot : serializersAndConfigs) {
				out.writeInt(bufferWithPos.getPosition());
				writeSerializer(bufferWrapper, serAndConfSnapshot.f0);

				out.writeInt(bufferWithPos.getPosition());
				writeSerializerConfigSnapshot(bufferWrapper, serAndConfSnapshot.f1);
			}

			out.writeInt(bufferWithPos.getPosition());
			out.write(bufferWithPos.getBuf(), 0, bufferWithPos.getPosition());
		}
	}

	/**
	 * Reads from a data input view a list of serializers and their corresponding config snapshots
	 * written using {@link #writeSerializersAndConfigsWithResilience(DataOutputView, List)}.
	 * This is fault tolerant to any failures when deserializing the serializers. Serializers which
	 * were not successfully deserialized will be replaced by {@code null}.
	 *
	 * @param in the data input view.
	 * @param userCodeClassLoader the user code class loader to use.
	 *
	 * @return the deserialized serializer and config snapshot pairs.
	 *
	 * @throws IOException
	 */
	public static List<Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>> readSerializersAndConfigsWithResilience(
			DataInputView in,
			ClassLoader userCodeClassLoader) throws IOException {

		int numSerializersAndConfigSnapshots = in.readInt();

		int[] offsets = new int[numSerializersAndConfigSnapshots * 2];

		for (int i = 0; i < numSerializersAndConfigSnapshots; i++) {
			offsets[i * 2] = in.readInt();
			offsets[i * 2 + 1] = in.readInt();
		}

		int totalBytes = in.readInt();
		byte[] buffer = new byte[totalBytes];
		in.readFully(buffer);

		List<Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>> serializersAndConfigSnapshots =
			new ArrayList<>(numSerializersAndConfigSnapshots);

		TypeSerializer<?> serializer;
		TypeSerializerConfigSnapshot configSnapshot;
		try (
			ByteArrayInputStreamWithPos bufferWithPos = new ByteArrayInputStreamWithPos(buffer);
			DataInputViewStreamWrapper bufferWrapper = new DataInputViewStreamWrapper(bufferWithPos)) {

			for (int i = 0; i < numSerializersAndConfigSnapshots; i++) {

				bufferWithPos.setPosition(offsets[i * 2]);
				serializer = tryReadSerializer(bufferWrapper, userCodeClassLoader);

				bufferWithPos.setPosition(offsets[i * 2 + 1]);
				configSnapshot = readSerializerConfigSnapshot(bufferWrapper, userCodeClassLoader);

				serializersAndConfigSnapshots.add(
					new Tuple2<TypeSerializer<?>, TypeSerializerConfigSnapshot>(serializer, configSnapshot));
			}
		}

		return serializersAndConfigSnapshots;
	}

	/**
	 * Writes a {@link TypeSerializerConfigSnapshot} to the provided data output view.
	 *
	 * <p>It is written with a format that can be later read again using
	 * {@link #readSerializerConfigSnapshot(DataInputView, ClassLoader)}.
	 *
	 * @param out the data output view
	 * @param serializerConfigSnapshot the serializer configuration snapshot to write
	 *
	 * @throws IOException
	 */
	public static void writeSerializerConfigSnapshot(
			DataOutputView out,
			TypeSerializerConfigSnapshot serializerConfigSnapshot) throws IOException {

		new TypeSerializerConfigSnapshotSerializationProxy(serializerConfigSnapshot).write(out);
	}

	/**
	 * Reads from a data input view a {@link TypeSerializerConfigSnapshot} that was previously
	 * written using {@link #writeSerializerConfigSnapshot(DataOutputView, TypeSerializerConfigSnapshot)}.
	 *
	 * @param in the data input view
	 * @param userCodeClassLoader the user code class loader to use
	 *
	 * @return the read serializer configuration snapshot
	 *
	 * @throws IOException
	 */
	public static TypeSerializerConfigSnapshot readSerializerConfigSnapshot(
			DataInputView in,
			ClassLoader userCodeClassLoader) throws IOException {

		final TypeSerializerConfigSnapshotSerializationProxy proxy = new TypeSerializerConfigSnapshotSerializationProxy(userCodeClassLoader);
		proxy.read(in);

		return proxy.getSerializerConfigSnapshot();
	}

	/**
	 * Writes multiple {@link TypeSerializerConfigSnapshot}s to the provided data output view.
	 *
	 * <p>It is written with a format that can be later read again using
	 * {@link #readSerializerConfigSnapshots(DataInputView, ClassLoader)}.
	 *
	 * @param out the data output view
	 * @param serializerConfigSnapshots the serializer configuration snapshots to write
	 *
	 * @throws IOException
	 */
	public static void writeSerializerConfigSnapshots(
			DataOutputView out,
			TypeSerializerConfigSnapshot... serializerConfigSnapshots) throws IOException {

		out.writeInt(serializerConfigSnapshots.length);

		for (TypeSerializerConfigSnapshot snapshot : serializerConfigSnapshots) {
			new TypeSerializerConfigSnapshotSerializationProxy(snapshot).write(out);
		}
	}

	/**
	 * Reads from a data input view multiple {@link TypeSerializerConfigSnapshot}s that was previously
	 * written using {@link #writeSerializerConfigSnapshot(DataOutputView, TypeSerializerConfigSnapshot)}.
	 *
	 * @param in the data input view
	 * @param userCodeClassLoader the user code class loader to use
	 *
	 * @return the read serializer configuration snapshots
	 *
	 * @throws IOException
	 */
	public static TypeSerializerConfigSnapshot[] readSerializerConfigSnapshots(
			DataInputView in,
			ClassLoader userCodeClassLoader) throws IOException {

		int numFields = in.readInt();
		final TypeSerializerConfigSnapshot[] serializerConfigSnapshots = new TypeSerializerConfigSnapshot[numFields];

		TypeSerializerConfigSnapshotSerializationProxy proxy;
		for (int i = 0; i < numFields; i++) {
			proxy = new TypeSerializerConfigSnapshotSerializationProxy(userCodeClassLoader);
			proxy.read(in);
			serializerConfigSnapshots[i] = proxy.getSerializerConfigSnapshot();
		}

		return serializerConfigSnapshots;
	}

	// -----------------------------------------------------------------------------------------------------

	/**
	 * Utility serialization proxy for a {@link TypeSerializer}.
	 */
	public static final class TypeSerializerSerializationProxy<T> extends VersionedIOReadableWritable {

		private static final Logger LOG = LoggerFactory.getLogger(TypeSerializerSerializationProxy.class);

		private static final int VERSION = 1;

		private ClassLoader userClassLoader;
		private TypeSerializer<T> typeSerializer;
		private boolean useDummyPlaceholder;

		public TypeSerializerSerializationProxy(ClassLoader userClassLoader, boolean useDummyPlaceholder) {
			this.userClassLoader = userClassLoader;
			this.useDummyPlaceholder = useDummyPlaceholder;
		}

		public TypeSerializerSerializationProxy(ClassLoader userClassLoader) {
			this(userClassLoader, false);
		}

		public TypeSerializerSerializationProxy(TypeSerializer<T> typeSerializer) {
			this.typeSerializer = Preconditions.checkNotNull(typeSerializer);
			this.useDummyPlaceholder = false;
		}

		public TypeSerializer<T> getTypeSerializer() {
			return typeSerializer;
		}

		@Override
		public void write(DataOutputView out) throws IOException {
			super.write(out);

			if (typeSerializer instanceof UnloadableDummyTypeSerializer) {
				UnloadableDummyTypeSerializer<T> dummyTypeSerializer =
					(UnloadableDummyTypeSerializer<T>) this.typeSerializer;

				byte[] serializerBytes = dummyTypeSerializer.getActualBytes();
				out.write(serializerBytes.length);
				out.write(serializerBytes);
			} else {
				// write in a way that allows the stream to recover from exceptions
				try (ByteArrayOutputStreamWithPos streamWithPos = new ByteArrayOutputStreamWithPos()) {
					InstantiationUtil.serializeObject(streamWithPos, typeSerializer);
					out.writeInt(streamWithPos.getPosition());
					out.write(streamWithPos.getBuf(), 0, streamWithPos.getPosition());
				}
			}
		}

		@SuppressWarnings("unchecked")
		@Override
		public void read(DataInputView in) throws IOException {
			super.read(in);

			// read in a way that allows the stream to recover from exceptions
			int serializerBytes = in.readInt();
			byte[] buffer = new byte[serializerBytes];
			in.readFully(buffer);

			ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
			try (
				FailureTolerantObjectInputStream ois =
					new FailureTolerantObjectInputStream(new ByteArrayInputStream(buffer), userClassLoader)) {

				Thread.currentThread().setContextClassLoader(userClassLoader);
				typeSerializer = (TypeSerializer<T>) ois.readObject();
			} catch (ClassNotFoundException | InvalidClassException e) {
				if (useDummyPlaceholder) {
					// we create a dummy so that all the information is not lost when we get a new checkpoint before receiving
					// a proper typeserializer from the user
					typeSerializer =
						new UnloadableDummyTypeSerializer<>(buffer);
					LOG.warn("Could not find requested TypeSerializer class in classpath. Created dummy.", e);
				} else {
					throw new IOException("Unloadable class for type serializer.", e);
				}
			} finally {
				Thread.currentThread().setContextClassLoader(previousClassLoader);
			}
		}

		@Override
		public int getVersion() {
			return VERSION;
		}
	}

	/**
	 * Utility serialization proxy for a {@link TypeSerializerConfigSnapshot}.
	 */
	static final class TypeSerializerConfigSnapshotSerializationProxy extends VersionedIOReadableWritable {

		private static final int VERSION = 1;

		private ClassLoader userCodeClassLoader;
		private TypeSerializerConfigSnapshot serializerConfigSnapshot;

		TypeSerializerConfigSnapshotSerializationProxy(ClassLoader userCodeClassLoader) {
			this.userCodeClassLoader = Preconditions.checkNotNull(userCodeClassLoader);
		}

		TypeSerializerConfigSnapshotSerializationProxy(TypeSerializerConfigSnapshot serializerConfigSnapshot) {
			this.serializerConfigSnapshot = serializerConfigSnapshot;
		}

		@Override
		public void write(DataOutputView out) throws IOException {
			super.write(out);

			// config snapshot class, so that we can re-instantiate the
			// correct type of config snapshot instance when deserializing
			out.writeUTF(serializerConfigSnapshot.getClass().getName());

			// the actual configuration parameters
			serializerConfigSnapshot.write(out);
		}

		@SuppressWarnings("unchecked")
		@Override
		public void read(DataInputView in) throws IOException {
			super.read(in);

			String serializerConfigClassname = in.readUTF();
			Class<? extends TypeSerializerConfigSnapshot> serializerConfigSnapshotClass;
			try {
				serializerConfigSnapshotClass = (Class<? extends TypeSerializerConfigSnapshot>)
					Class.forName(serializerConfigClassname, true, userCodeClassLoader);
			} catch (ClassNotFoundException e) {
				throw new IOException(
					"Could not find requested TypeSerializerConfigSnapshot class "
						+ serializerConfigClassname +  " in classpath.", e);
			}

			serializerConfigSnapshot = InstantiationUtil.instantiate(serializerConfigSnapshotClass);
			serializerConfigSnapshot.setUserCodeClassLoader(userCodeClassLoader);
			serializerConfigSnapshot.read(in);
		}

		@Override
		public int getVersion() {
			return VERSION;
		}

		TypeSerializerConfigSnapshot getSerializerConfigSnapshot() {
			return serializerConfigSnapshot;
		}
	}
}
