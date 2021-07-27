/*
 * Copyright (c) 2010-2020. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.serialization;

import com.fasterxml.jackson.annotation.JsonCreator.Mode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.thoughtworks.xstream.XStream;
import org.axonframework.serialization.json.JacksonSerializer;
import org.axonframework.serialization.xml.XStreamSerializer;

import java.beans.ConstructorProperties;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;

/**
 * Enumeration of serializers for testing purposes.
 *
 * @author JohT
 */
@SuppressWarnings("unused")
public enum TestSerializer {

    JAVA {
        @SuppressWarnings("deprecation")
        private final Serializer serializer = JavaSerializer.builder().build();

        @Override
        public Serializer getSerializer() {
            return serializer;
        }

        @Override
        protected String serialize(Object object) {
            return Base64.getEncoder().encodeToString(getSerializer().serialize(object, byte[].class).getData());
        }

        @Override
        protected <T> T deserialize(String serialized, Class<T> type) {
            return getSerializer().deserialize(asSerializedData(Base64.getDecoder().decode(serialized), type));
        }
    },
    XSTREAM {
        private final Serializer serializer = createSerializer();

        private XStreamSerializer createSerializer() {
            XStream xStream = new XStream();
            xStream.setClassLoader(this.getClass().getClassLoader());
            xStream.allowTypesByWildcard(new String[]{"org.axonframework.**"});
            return XStreamSerializer.builder().xStream(xStream).build();
        }

        @Override
        public Serializer getSerializer() {
            return serializer;
        }
    },
    JACKSON {
        private final Serializer serializer = JacksonSerializer.builder().build();

        @Override
        public Serializer getSerializer() {
            return serializer;
        }
    },
    JACKSON_ONLY_ACCEPT_CONSTRUCTOR_PARAMETERS {
        private final Serializer serializer =
                JacksonSerializer.builder()
                                 .objectMapper(OnlyAcceptConstructorPropertiesAnnotation.attachTo(new ObjectMapper()))
                                 .build();

        @Override
        public Serializer getSerializer() {
            return serializer;
        }
    };

    protected String serialize(Object object) {
        return new String(getSerializer().serialize(object, byte[].class).getData());
    }

    protected <T> T deserialize(String serialized, Class<T> type) {
        return getSerializer().deserialize(asSerializedData(serialized.getBytes(), type));
    }

    public abstract Serializer getSerializer();

    @SuppressWarnings("unchecked")
    public <T> T serializeDeserialize(T object) {
        return deserialize(serialize(object), (Class<T>) object.getClass());
    }

    public static Collection<TestSerializer> all() {
        return EnumSet.allOf(TestSerializer.class);
    }

    static <T> SerializedObject<byte[]> asSerializedData(byte[] serialized, Class<T> type) {
        SimpleSerializedType serializedType = new SimpleSerializedType(type.getName(), null);
        return new SimpleSerializedObject<>(serialized, byte[].class, serializedType);
    }

    private static class OnlyAcceptConstructorPropertiesAnnotation extends JacksonAnnotationIntrospector {

        private static final long serialVersionUID = 1L;

        public static ObjectMapper attachTo(ObjectMapper objectMapper) {
            return objectMapper.setAnnotationIntrospector(new OnlyAcceptConstructorPropertiesAnnotation());
        }

        @Override
        public Mode findCreatorAnnotation(MapperConfig<?> config, Annotated annotated) {
            return (annotated.hasAnnotation(ConstructorProperties.class))
                    ? super.findCreatorAnnotation(config, annotated)
                    : Mode.DISABLED;
        }
    }
}