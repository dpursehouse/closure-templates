/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.types.proto;


import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.protobuf.DescriptorProtos.FieldOptions.JSType;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.google.protobuf.Descriptors.FieldDescriptor.Type;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor.Syntax;
import com.google.protobuf.Descriptors.GenericDescriptor;

/** A collection of protobuf utility methods. */
public final class ProtoUtils {

  private ProtoUtils() {
    // Static only.
  }

  /** Returns true if fieldDescriptor holds a sanitized proto type. */
  public static boolean isSanitizedContentField(FieldDescriptor fieldDescriptor) {
    return fieldDescriptor.getType() == Type.MESSAGE
        && SafeStringTypes.SAFE_PROTO_TO_SANITIZED_TYPE.containsKey(
            fieldDescriptor.getMessageType().getFullName());
  }

  /** Returns the proper .getDescriptor() call for parse info generation in Tofu. */
  public static String getTofuExtensionImport(FieldDescriptor desc) {
    // This is run by GenerateParseInfoVisitor, which doesn't necessarily have a classpath
    // dependency on the proto, just a data dependency on the descriptor.

    String extensionFieldName = JavaQualifiedNames.getFieldName(desc, false);

    String extensionFieldHolderClassName;
    if (desc.getExtensionScope() != null) {
      extensionFieldHolderClassName = JavaQualifiedNames.getQualifiedName(desc.getExtensionScope());
    } else {
      // else we have a 'top level extension'
      extensionFieldHolderClassName =
          JavaQualifiedNames.getPackage(desc.getFile())
              + "."
              + JavaQualifiedNames.getOuterClassname(desc.getFile());
    }
    return extensionFieldHolderClassName + "." + extensionFieldName + ".getDescriptor()";
  }

  /** Returns the JS name of the import for the given extension, suitable for goog.require. */
  public static String getJsExtensionImport(FieldDescriptor desc) {
    Descriptor scope = desc.getExtensionScope();
    if (scope != null) {
      while (scope.getContainingType() != null) {
        scope = scope.getContainingType();
      }
      return calculateQualifiedJsName(scope);
    }
    return getJsPackage(desc.getFile()) + "." + computeJsExtensionName(desc);
  }

  /** Returns the JS name of the extension, suitable for passing to getExtension(). */
  public static String getJsExtensionName(FieldDescriptor desc) {
    Descriptor scope = desc.getExtensionScope();
    if (scope != null) {
      return calculateQualifiedJsName(scope) + "." + computeJsExtensionName(desc);
    }
    return getJsPackage(desc.getFile()) + "." + computeJsExtensionName(desc);
  }

  /** Performs camelcase translation. */
  private static String computeJsExtensionName(FieldDescriptor field) {
    String name = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, field.getName());
    return field.isRepeated() ? name + "List" : name;
  }

  /** Returns the expected javascript package for protos based on the .proto file. */
  private static String getJsPackage(FileDescriptor file) {
    String protoPackage = file.getPackage();
    if (!protoPackage.isEmpty()) {
      return "proto." + protoPackage;
    }
    return "proto";
  }

  static boolean shouldJsIgnoreField(FieldDescriptor fieldDescriptor) {
    return false;
  }

  static boolean hasJsMapKey(FieldDescriptor fieldDescriptor) {
    return false;
  }

  static String getJsMapKeyFieldName(FieldDescriptor fieldDescriptor) {
    return null;
  }

  /** Only int64 fields can have jstype annotations. */
  private static final ImmutableSet<Type> JS_TYPEABLE_FIELDS =
      Sets.immutableEnumSet(Type.INT64, Type.SFIXED64, Type.UINT64, Type.FIXED64, Type.SINT64);

  /** Returns true if this field has a valid jstype annotation. */
  public static boolean hasJsType(FieldDescriptor fieldDescriptor) {
    if (!JS_TYPEABLE_FIELDS.contains(fieldDescriptor.getType())) {
      return false;
    }
    if (fieldDescriptor.getOptions().hasJstype()) {
      return true;
    }
    return false;
  }

  /** Returns true if this field is an unsigned integer. */
  public static boolean isUnsigned(FieldDescriptor descriptor) {
    switch (descriptor.getType()) {
      case FIXED32:
      case FIXED64:
      case UINT32:
      case UINT64:
        return true;
      default:
        return false;
    }
  }

  public static JSType getJsType(FieldDescriptor fieldDescriptor) {
    boolean hasJstype = fieldDescriptor.getOptions().hasJstype();
    if (hasJstype) {
      return fieldDescriptor.getOptions().getJstype();
    }
    return null;
  }

  static String calculateJsEnumName(EnumDescriptor descriptor) {
    return calculateQualifiedJsName(descriptor);
  }

  static String calculateQualifiedJsName(GenericDescriptor descriptor) {
    String protoPackage = descriptor.getFile().getPackage();
    // We need a semi-qualified name: including containing types but not the package.
    String name = descriptor.getFullName();
    if (!name.startsWith(protoPackage)) {
      throw new AssertionError("Expected \"" + name + "\" to start with \"" + protoPackage + "\"");
    }
    String jsPackage = getJsPackage(descriptor.getFile());
    // When there is no protoPackage, the semi-qualified name does not have a package prefix nor the
    // "." separator.
    if (protoPackage.isEmpty()) {
      return jsPackage + "." + name;
    }
    return jsPackage + name.substring(protoPackage.length());
  }

  /**
   * Returns whether or not we should check for presence to emulate jspb nullability semantics in
   * server side soy.
   */
  static boolean shouldCheckFieldPresenceToEmulateJspbNullability(FieldDescriptor desc) {
    boolean hasBrokenSemantics = false;
    if (desc.hasDefaultValue() || desc.isRepeated()) {
      return false;
    } else if (desc.getFile().getSyntax() == Syntax.PROTO3 || !hasBrokenSemantics) {
      // in proto3 or proto2 with non-broken semantics we only need to check for presence for
      // message typed fields.
      return desc.getJavaType() == JavaType.MESSAGE;
    } else {
      return true;
    }
  }


}
