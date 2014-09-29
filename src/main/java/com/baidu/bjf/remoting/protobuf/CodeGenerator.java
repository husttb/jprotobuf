/*
 * Copyright 2002-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.baidu.bjf.remoting.protobuf;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;

import com.baidu.bjf.remoting.protobuf.annotation.Protobuf;

/**
 * @author xiemalin
 * @since 1.0.0
 */
public class CodeGenerator {
    
    /**
     * auto proxied suffix class name
     */
    private static final String DEFAULT_SUFFIX_CLASSNAME = "$$JProtoBufClass";

    /**
     * Logger for this class
     */
    private static final Logger LOGGER = Logger.getLogger(CodeGenerator.class);

    /**
     * target fields which marked <code> @Protofuf </code> annotation
     */
    private List<Field> fields;
    
    /**
     * target class
     */
    private Class<?> cls;

    /**
     * Constructor
     * 
     * @param fields
     *            protobuf mapped fields
     * @param cls
     *            protobuf mapped class
     */
    public CodeGenerator(List<Field> fields, Class<?> cls) {
        super();
        this.fields = fields;
        this.cls = cls;
    }
    
    /**
     * get new class name
     * @return class name
     */
    public String getClassName() {
        return cls.getSimpleName() + DEFAULT_SUFFIX_CLASSNAME;
    }
    
    /**
     * get new class name with full package
     * @return class name
     */
    public String getFullClassName() {
        return getPackage() + "." + getClassName();
    }
    
    public String getPackage() {
        Package pkg = cls.getPackage();
        // maybe null if package is blank or dynamic load class
        if (pkg == null) {
            String fullName = cls.getName();
            int index = fullName.lastIndexOf('.');
            if (index != -1) {
                return fullName.substring(0, index);
            }
            return "";
        }
        
        return pkg.getName();
    }
    
    /**
     * generate package code
     * @param code
     */
    private void genPackageCode(StringBuilder code) {
        code.append("package " + getPackage() + ";\n");
    }

    /**
     * get full java class code.
     * 
     * @return full java class code
     */
    public String getCode() {
        StringBuilder code = new StringBuilder();

        String className = getClassName();
        genPackageCode(code);
        genImportCode(code);

        code.append("public class " + className
                + " implements com.baidu.bjf.remoting.protobuf.Codec");
        code.append("<").append(cls.getName()).append("> {\n");

        code.append(getEncodeMethodCode());
        code.append(getDecodeMethodCode());
        code.append(getSizeMethodCode());
        code.append(getWriteToMethodCode());
        code.append(getReadFromMethodCode());
        code.append("}");

        return code.toString();
    }

    /**
     * generate import code
     * @param code
     */
    private void genImportCode(StringBuilder code) {
        code.append("import com.google.protobuf.*;\n");
        code.append("import java.io.IOException;\n");
        code.append("import com.baidu.bjf.remoting.protobuf.utils.*;\n");
        code.append("import java.lang.reflect.*;\n");
        code.append("import com.baidu.bjf.remoting.protobuf.*;\n");
        code.append("import java.util.*;\n");
        
        code.append("import ").append(cls.getName()).append(";\n");
    }

    /**
     * generate <code>decode</code> method source code
     * @return
     */
    private String getDecodeMethodCode() {
        StringBuilder code = new StringBuilder();

        code.append("public ").append(cls.getSimpleName())
                .append(" decode(byte[] bb) throws IOException {\n");
        code.append(cls.getName()).append(" ret = new ")
                .append(cls.getName()).append("();");
        code.append("CodedInputStream input = CodedInputStream.newInstance(bb, 0, bb.length);\n");
        code.append("try {\n");
        code.append("boolean done = false;\n");
        code.append("Codec codec = null;\n");
        code.append("while (!done) {\n");
        code.append("int tag = input.readTag();\n");
        code.append("if (tag == 0) { break;}\n");

        for (Field field : fields) {
            boolean isList = isListType(field);
            
            Protobuf protobuf = field.getAnnotation(Protobuf.class);
            code.append("if (tag == CodedConstant.makeTag(").append(
                    protobuf.order());
            code.append(",WireFormat.")
                    .append(protobuf.fieldType().getWireFormat())
                    .append(")) {\n");
            String t = protobuf.fieldType().getType();
            t = CodedConstant.capitalize(t);
            
            boolean listTypeCheck = false;
            String express = "input.read" + t + "()";
            if (isList && protobuf.fieldType() == FieldType.OBJECT) {
                Type type = field.getGenericType();
                if (type instanceof ParameterizedType) {
                    ParameterizedType ptype = (ParameterizedType) type;
                    
                    Type[] actualTypeArguments = ptype.getActualTypeArguments();
                    
                    if (actualTypeArguments != null && actualTypeArguments.length > 0) {
                        Type targetType = actualTypeArguments[0];
                        if (targetType instanceof Class) {
                            Class cls = (Class) targetType;
                            code.append("codec = ProtobufProxy.create(").append(cls.getName()).append(".class);\n");
                            code.append("int length = input.readRawVarint32();\n");
                            code.append("final int oldLimit = input.pushLimit(length);\n");
                            listTypeCheck = true;
                            express = "(" + cls.getSimpleName() + ") codec.readFrom(input)";
                        }
                    }
                    
                }
            } else if (protobuf.fieldType() == FieldType.OBJECT) {
                Class cls = field.getType();
                code.append("codec = ProtobufProxy.create(").append(cls.getName()).append(".class);\n");
                code.append("int length = input.readRawVarint32();\n");
                code.append("final int oldLimit = input.pushLimit(length);\n");
                listTypeCheck = true;
                express = "(" + cls.getSimpleName() + ") codec.readFrom(input)";
            }
            
            
            code.append(
                    getSetToField("ret", field, cls, express, isList));
            if (protobuf.fieldType() == FieldType.BYTES) {
                code.append(".toByteArray()");
            }
                    
            code.append(";\n");
            
            if (listTypeCheck) {
                code.append("input.checkLastTagWas(0);\n");
                code.append("input.popLimit(oldLimit);\n");
            }
            
            code.append("continue;\n");
            code.append("}");

        }

        code.append("input.skipField(tag);\n");
        code.append("}");
        code.append("} catch (com.google.protobuf.InvalidProtocolBufferException e) {");
        code.append("throw e;");
        code.append("} catch (java.io.IOException e) {");
        code.append("throw e;");
        code.append("}");

        for (Field field : fields) {
            Protobuf protobuf = field.getAnnotation(Protobuf.class);
            if (protobuf.required()) {
                code.append(CodedConstant.getRetRequiredCheck(
                        getAccessByField("ret", field, cls), field));
            }

        }

        code.append("return ret;\n");

        code.append("}\n");

        return code.toString();
    }
 
    /**
     * generate <code>readFrom</code> method source code
     * @return
     */
    private String getReadFromMethodCode() {
        StringBuilder code = new StringBuilder();

        code.append("public ").append(cls.getSimpleName())
                .append(" readFrom(CodedInputStream input) throws IOException {\n");
        code.append(cls.getName()).append(" ret = new ")
                .append(cls.getName()).append("();");
        code.append("try {\n");
        code.append("boolean done = false;\n");
        code.append("Codec codec = null;\n");
        code.append("while (!done) {\n");
        code.append("int tag = input.readTag();\n");
        code.append("if (tag == 0) { break;}\n");

        for (Field field : fields) {
            boolean isList = isListType(field);
            
            Protobuf protobuf = field.getAnnotation(Protobuf.class);
            code.append("if (tag == CodedConstant.makeTag(").append(
                    protobuf.order());
            code.append(",WireFormat.")
                    .append(protobuf.fieldType().getWireFormat())
                    .append(")) {\n");
            String t = protobuf.fieldType().getType();
            t = CodedConstant.capitalize(t);
            
            boolean listTypeCheck = false;
            String express = "input.read" + t + "()";
            if (isList && protobuf.fieldType() == FieldType.OBJECT) {
                Type type = field.getGenericType();
                if (type instanceof ParameterizedType) {
                    ParameterizedType ptype = (ParameterizedType) type;
                    
                    Type[] actualTypeArguments = ptype.getActualTypeArguments();
                    
                    if (actualTypeArguments != null && actualTypeArguments.length > 0) {
                        Type targetType = actualTypeArguments[0];
                        if (targetType instanceof Class) {
                            Class cls = (Class) targetType;
                            code.append("codec = ProtobufProxy.create(").append(cls.getSimpleName()).append(".class);\n");
                            code.append("int length = input.readRawVarint32();\n");
                            code.append("final int oldLimit = input.pushLimit(length);\n");
                            listTypeCheck = true;
                            express = "(" + cls.getSimpleName() + ") codec.readFrom(input)";
                        }
                    }
                    
                }
            } else if (protobuf.fieldType() == FieldType.OBJECT) {
                Class cls = field.getType();
                code.append("codec = ProtobufProxy.create(").append(cls.getName()).append(".class);\n");
                code.append("int length = input.readRawVarint32();\n");
                code.append("final int oldLimit = input.pushLimit(length);\n");
                listTypeCheck = true;
                express = "(" + cls.getSimpleName() + ") codec.readFrom(input)";
            }
            
            code.append(
                    getSetToField("ret", field, cls, express, isList));
            if (protobuf.fieldType() == FieldType.BYTES) {
                code.append(".toByteArray()");
            }
                    
            code.append(";\n");
            
            if (listTypeCheck) {
                code.append("input.checkLastTagWas(0);\n");
                code.append("input.popLimit(oldLimit);\n");
            }
            
            code.append("continue;\n");
            code.append("}");

        }

        code.append("input.skipField(tag);\n");
        code.append("}");
        code.append("} catch (com.google.protobuf.InvalidProtocolBufferException e) {");
        code.append("throw e;");
        code.append("} catch (java.io.IOException e) {");
        code.append("throw e;");
        code.append("}");

        for (Field field : fields) {
            Protobuf protobuf = field.getAnnotation(Protobuf.class);
            if (protobuf.required()) {
                code.append(CodedConstant.getRetRequiredCheck(
                        getAccessByField("ret", field, cls), field));
            }

        }

        code.append("return ret;\n");

        code.append("}\n");

        return code.toString();
    }
    
    /**
     * To check if type of {@link Field} is assignable from {@link List}
     * 
     * @param field
     * @return true if is assignable from {@link List}
     */
    public static boolean isListType(Field field) {
        Class<?> cls = field.getType();
        if (List.class.isAssignableFrom(cls)) {
            //if check is list ignore check
            return true;
        }
        return false;
    }

    /**
     * Check {@link FieldType} is validate to class type of {@link Field}
     * 
     * @param type
     * @param field
     */
    private void checkType(FieldType type, Field field) {
        Class<?> cls = field.getType();
        
        if (type == FieldType.OBJECT) {
            return;
        }
        
        String javaType = type.getJavaType();
        if ("Integer".equals(javaType)) {
            if (cls.getSimpleName().equals("int")
                    || "Integer".equals(cls.getSimpleName())) {
                return;
            }
            throw new IllegalArgumentException(getMismatchTypeErroMessage(type, field));
        }
        if (!javaType.equalsIgnoreCase(cls.getSimpleName())) {
            throw new IllegalArgumentException(getMismatchTypeErroMessage(type, field));
        }
    }

    /**
     * get error message info by type not matched
     * 
     * @param type
     * @param field
     * @return
     */
    private String getMismatchTypeErroMessage(FieldType type, Field field) {
        return "Type mismatch. @Protobuf required type '" + type.getJavaType()
                + "' but field type is '" + field.getType().getSimpleName()
                + "'";
    }

    /**
     * generate <code>encode</code> method source code
     * @return
     */   
    private String getEncodeMethodCode() {
        StringBuilder code = new StringBuilder();
        Set<Integer> orders = new HashSet<Integer>();
        // encode method
        code.append("public byte[] encode(").append(cls.getSimpleName())
                .append(" t) throws IOException {\n");
        code.append("int size = 0;");
        for (Field field : fields) {
            Protobuf protobuf = field.getAnnotation(Protobuf.class);
            
            boolean isList = isListType(field);
            
            // check type
            if (!isList) {
                checkType(protobuf.fieldType(), field);
            }

            if (orders.contains(protobuf.order())) {
                throw new IllegalArgumentException("Field order '"
                        + protobuf.order() + "' on field" + field.getName()
                        + " already exsit.");
            }
            // define field
            code.append(CodedConstant.getMappedTypeDefined(protobuf.order(),
                    protobuf.fieldType(), getAccessByField("t", field, cls), isList));
            // compute size
            code.append("if (!CodedConstant.isNull(")
                    .append(getAccessByField("t", field, cls)).append("))\n");
            code.append("{\nsize+=");
            code.append(CodedConstant.getMappedTypeSize(protobuf.order(),
                    protobuf.fieldType(), isList));
            code.append("}\n");
            if (protobuf.required()) {
                code.append(CodedConstant.getRequiredCheck(protobuf.order(),
                        field));
            }
        }

        code.append("final byte[] result = new byte[size];\n");
        code.append("final CodedOutputStream output = CodedOutputStream.newInstance(result);\n");
        for (Field field : fields) {
            boolean isList = isListType(field);
            Protobuf protobuf = field.getAnnotation(Protobuf.class);
            // set write to byte
            code.append(CodedConstant.getMappedWriteCode("output", protobuf.order(),
                    protobuf.fieldType(), isList));
        }

        code.append("return result;\n");
        code.append("}\n");

        return code.toString();
    }
    
    
    /**
     * generate <code>writeTo</code> method source code
     * @return
     */ 
    private String getWriteToMethodCode() {
        StringBuilder code = new StringBuilder();
        Set<Integer> orders = new HashSet<Integer>();
        // encode method
        code.append("public void writeTo(").append(cls.getSimpleName())
                .append(" t, CodedOutputStream output) throws IOException {\n");
        code.append("int size = 0;");
        for (Field field : fields) {
            Protobuf protobuf = field.getAnnotation(Protobuf.class);
            
            boolean isList = isListType(field);
            
            // check type
            if (!isList) {
                checkType(protobuf.fieldType(), field);
            }

            if (orders.contains(protobuf.order())) {
                throw new IllegalArgumentException("Field order '"
                        + protobuf.order() + "' on field" + field.getName()
                        + " already exsit.");
            }
            // define field
            code.append(CodedConstant.getMappedTypeDefined(protobuf.order(),
                    protobuf.fieldType(), getAccessByField("t", field, cls), isList));
            if (protobuf.required()) {
                code.append(CodedConstant.getRequiredCheck(protobuf.order(),
                        field));
            }
        }

        for (Field field : fields) {
            boolean isList = isListType(field);
            Protobuf protobuf = field.getAnnotation(Protobuf.class);
            // set write to byte
            code.append(CodedConstant.getMappedWriteCode("output", protobuf.order(),
                    protobuf.fieldType(), isList));
        }

        code.append("}\n");

        return code.toString();
    }

    /**
     * generate <code>size</code> method source code
     * @return
     */ 
    private String getSizeMethodCode() {
        StringBuilder code = new StringBuilder();
        Set<Integer> orders = new HashSet<Integer>();
        // encode method
        code.append("public int size(").append(cls.getSimpleName())
                .append(" t) throws IOException {\n");
        code.append("int size = 0;");
        for (Field field : fields) {
            Protobuf protobuf = field.getAnnotation(Protobuf.class);
            
            boolean isList = isListType(field);
            
            // check type
            if (!isList) {
                checkType(protobuf.fieldType(), field);
            }

            if (orders.contains(protobuf.order())) {
                throw new IllegalArgumentException("Field order '"
                        + protobuf.order() + "' on field" + field.getName()
                        + " already exsit.");
            }
            // define field
            code.append(CodedConstant.getMappedTypeDefined(protobuf.order(),
                    protobuf.fieldType(), getAccessByField("t", field, cls), isList));
            // compute size
            code.append("if (!CodedConstant.isNull(")
                    .append(getAccessByField("t", field, cls)).append("))\n");
            code.append("{\nsize+=");
            code.append(CodedConstant.getMappedTypeSize(protobuf.order(),
                    protobuf.fieldType(), isList));
            code.append("}\n");
            if (protobuf.required()) {
                code.append(CodedConstant.getRequiredCheck(protobuf.order(),
                        field));
            }
        }

        code.append("return size;\n");
        code.append("}\n");

        return code.toString();
    }
    
    /**
     * get field access code
     * 
     * @param target
     *            target instance name
     * @param field
     *            java field instance
     * @param cls
     *            mapped class
     * @return full field access java code
     */
    protected String getAccessByField(String target, Field field, Class<?> cls) {
        if (field.getModifiers() == Modifier.PUBLIC) {
            return target + "." + field.getName();
        }
        // check if has getter method
        String getter;
        if ("boolean".equalsIgnoreCase(field.getType().getName())) {
            getter = "is" + CodedConstant.capitalize(field.getName());
        } else {
            getter = "get" + CodedConstant.capitalize(field.getName());
        }
        // check method exist
        try {
            cls.getMethod(getter, new Class<?>[0]);
            return target + "." + getter + "()";
        } catch (Exception e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(e.getMessage(), e);
            }
        }
        // use reflection to get value
        String code = "(" + field.getType().getName() + ") ";
        code += "FieldUtils.getField(" + target + ", \"" + field.getName()
                + "\")";

        return code;
    }

    /**
     * generate access {@link Field} value source code.
     * support public field access, getter method access and reflection access.
     * 
     * @param target
     * @param field
     * @param cls
     * @param express
     * @param isList
     * @return
     */
    protected String getSetToField(String target, Field field, Class<?> cls,
            String express, boolean isList) {
        String ret = "";
        if (isList) {
            ret = "if ((" + getAccessByField(target, field, cls) + ") == null) {\n";
        }
        if (field.getModifiers() == Modifier.PUBLIC) {
            if (isList) {
                ret += target + "." + field.getName() + "= new ArrayList();\n}";  
                ret +=  target + "." + field.getName() + ".add(" + express + ")";
                return ret;
            }
            return target + "." + field.getName() + "=" + express + "\n";
        }
        String setter = "set" + CodedConstant.capitalize(field.getName());
        // check method exist
        try {
            cls.getMethod(setter, new Class<?>[]{field.getType()});
            if (isList) {
                ret += "List __list = new ArrayList();\n";
                ret += target + "." + setter + "(__list);\n}";
                
                ret += "(" + getAccessByField(target, field, cls) + ").add(" + express + ")";
                return ret;
            }
            
            return target + "." + setter + "(" + express + ")\n";
        } catch (Exception e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(e.getMessage(), e);
            }
        }

        if (isList) {
            ret += "List __list = new ArrayList();\n";
            ret += "FieldUtils.setField(" + target + ", \"" + 
                    field.getName() + "\", __list);\n}";
            
            ret += "(" + getAccessByField(target, field, cls) + ").add(" + express + ")";
            return ret;
        }
        
        // use reflection to get value
        String code = "FieldUtils.setField(" + target + ", \"" + 
            field.getName() + "\", " + express + ")\n";
        return code;
    }

}
