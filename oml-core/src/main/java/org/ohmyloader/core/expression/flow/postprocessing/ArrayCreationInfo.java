package org.ohmyloader.core.expression.flow.postprocessing;

import org.ohmyloader.core.expression.flow.FlowValue;
import org.ohmyloader.core.expression.flow.utils.InsnReference;

public class ArrayCreationInfo {
    public final InsnReference initialized;

    public ArrayCreationInfo(FlowValue initialized) {
        this.initialized = new InsnReference(initialized);
    }
}
