package net.okitsu.ysmmapping.api;

import java.io.IOException;
import java.util.Collection;

public interface YsmMappingProvider {
    MappingSnapshot resolve(String consumerModId, Collection<YsmSymbolKey<?>> keys)
            throws IOException;

    MappingSnapshot current() throws IOException;
}
