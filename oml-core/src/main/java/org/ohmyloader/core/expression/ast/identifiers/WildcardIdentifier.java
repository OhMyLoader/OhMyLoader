package org.ohmyloader.core.expression.ast.identifiers;

import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.pool.IdentifierPool;
import org.objectweb.asm.Type;

public class WildcardIdentifier implements MemberIdentifier, TypeIdentifier {
    @Override
    public boolean matches(IdentifierPool pool, FlowValue node) {
        return true;
    }

    @Override
    public boolean matches(IdentifierPool pool, Type type) {
        return true;
    }
}
