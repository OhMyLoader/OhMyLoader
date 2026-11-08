package org.ohmyloader.core.expression.ast.identifiers;

import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.pool.IdentifierPool;

public class DefinedMemberIdentifier implements MemberIdentifier {
    public final String name;

    public DefinedMemberIdentifier(String name) {
        this.name = name;
    }

    @Override
    public boolean matches(IdentifierPool pool, FlowValue node) {
        return pool.matchesMember(name, node);
    }
}
