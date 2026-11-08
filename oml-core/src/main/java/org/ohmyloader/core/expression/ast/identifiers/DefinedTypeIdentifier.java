package org.ohmyloader.core.expression.ast.identifiers;

import org.ohmyloader.core.expression.pool.IdentifierPool;
import org.objectweb.asm.Type;

public class DefinedTypeIdentifier implements TypeIdentifier {
    public final String name;

    public DefinedTypeIdentifier(String name) {
        this.name = name;
    }

    @Override
    public boolean matches(IdentifierPool pool, Type type) {
        return pool.matchesType(name, type);
    }
}
