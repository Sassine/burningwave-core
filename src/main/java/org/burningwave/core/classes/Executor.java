package org.burningwave.core.classes;

import org.burningwave.core.assembler.ComponentSupplier;

public interface Executor {
	
    public <T> T execute(ComponentSupplier componentSupplier, Object... parameters) throws Throwable;
	
}
