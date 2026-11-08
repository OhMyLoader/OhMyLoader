package org.ohmyloader.core.expression.flow.postprocessing;

import org.ohmyloader.core.expression.flow.FlowValue;
import org.objectweb.asm.Type;

public class InstantiationInfo {
    public final Type type;
    public final FlowValue initCall;

    public InstantiationInfo(Type type, FlowValue initCall) {
        this.type = type;
        this.initCall = initCall;
    }
}
