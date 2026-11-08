package org.ohmyloader.core.expression.pool;

import org.objectweb.asm.Type;

class ExactTypeDef implements TypeDefinition {
    private final Type type;

    ExactTypeDef(Type type) {
        this.type = type;
    }

    @Override
    public boolean matches(Type type) {
        return this.type.equals(type);
    }
}
