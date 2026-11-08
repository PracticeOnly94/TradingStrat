package com.tradingstrat.api.stub;

import com.tradingstrat.api.SymbolUniverse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Fixed synthetic universe for the "stub" profile, so universe backtests can be
 * exercised without network access.
 */
@Profile("stub")
@Component
public class StubSymbolUniverse implements SymbolUniverse {

    @Override
    public List<String> topByLiquidity(int limit) {
        return IntStream.range(0, limit)
                .mapToObj(i -> "STUB" + i + "USDT")
                .toList();
    }
}
