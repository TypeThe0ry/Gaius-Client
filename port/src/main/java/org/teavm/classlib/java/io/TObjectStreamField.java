package org.teavm.classlib.java.io;

public class TObjectStreamField implements Comparable<TObjectStreamField> {
    private final String name;
    private final Class<?> type;

    public TObjectStreamField(String name, Class<?> type) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public Class<?> getType() {
        return type;
    }

    public char getTypeCode() {
        if (type == boolean.class) {
            return 'Z';
        } else if (type == byte.class) {
            return 'B';
        } else if (type == char.class) {
            return 'C';
        } else if (type == short.class) {
            return 'S';
        } else if (type == int.class) {
            return 'I';
        } else if (type == long.class) {
            return 'J';
        } else if (type == float.class) {
            return 'F';
        } else if (type == double.class) {
            return 'D';
        }
        return type.isArray() ? '[' : 'L';
    }

    public String getTypeString() {
        return type.isPrimitive() ? null : type.getName();
    }

    public boolean isPrimitive() {
        return type.isPrimitive();
    }

    @Override
    public int compareTo(TObjectStreamField other) {
        return name.compareTo(other.name);
    }
}
