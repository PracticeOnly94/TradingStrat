package com.tradingstrat.service;

import com.tradingstrat.api.SymbolUniverse;
import com.tradingstrat.backtest.BacktestEngine;
import com.tradingstrat.backtest.BacktestReport;
import com.tradingstrat.backtest.UniverseBacktestReport;
import com.tradingstrat.model.BacktestRequest;
import com.tradingstrat.model.UniverseBacktestRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Runs a backtest across a universe of symbols and pools the results.
 *
 * Symbols run concurrently on virtual threads — each is an independent backtest whose
 * cost is dominated by waiting on the exchange API.
 *
 * A symbol that fails (delisted, insufficient history, API error) is recorded and
 * skipped rather than aborting the run: losing one market should not cost the sample.
 */
@Service
public class UniverseBacktestService {

    private static final Logger log = LoggerFactory.getLogger(UniverseBacktestService.class);

    private final SymbolUniverse symbolUniverse;
    private final BacktestService backtestService;

    /**
     * Cap on symbols fetched at once. Virtual threads make it trivial to launch fifty
     * concurrent backtests, but the exchange throttles bursts and returns an error body
     * instead of candles — which silently halves the sample rather than failing loudly.
     */
    private final Semaphore fetchPermits;

    public UniverseBacktestService(SymbolUniverse symbolUniverse,
                                   BacktestService backtestService,
                                   @Value("${tradingstrat.backtest.max-concurrent-symbols:5}")
                                   int maxConcurrentSymbols) {
        this.symbolUniverse = symbolUniverse;
        this.backtestService = backtestService;
        this.fetchPermits = new Semaphore(Math.max(1, maxConcurrentSymbols));
    }

    public UniverseBacktestReport run(UniverseBacktestRequest request) {
        List<String> symbols = resolveSymbols(request);
        log.info("Universe backtest over {} symbols on {}", symbols.size(), request.interval());

        List<Callable<Outcome>> tasks = symbols.stream()
                .map(symbol -> (Callable<Outcome>) () -> {
                    BacktestRequest single = new BacktestRequest(
                            symbol, request.interval(), request.htfInterval(), request.bars(),
                            request.feePct(), request.slippagePct(), request.warmupBars(), true,
                            request.maxHoldBars(), request.segment(), request.endDate());

                    // Two attempts: a throttled fetch is transient, and losing the symbol
                    // costs sample we cannot get back.
                    Exception last = null;
                    for (int attempt = 1; attempt <= 2; attempt++) {
                        fetchPermits.acquire();
                        try {
                            return new Outcome(symbol, backtestService.run(single), null);
                        } catch (Exception ex) {
                            last = ex;
                            log.warn("Backtest attempt {} failed for {}: {}", attempt, symbol, ex.getMessage());
                        } finally {
                            fetchPermits.release();
                        }
                        Thread.sleep(1000L * attempt);
                    }
                    return new Outcome(symbol, null, last == null ? "unknown" : last.getMessage());
                })
                .toList();

        List<Outcome> outcomes = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Outcome> future : executor.invokeAll(tasks)) {
                outcomes.add(future.get());
            }
        } catch (Exception ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Universe backtest interrupted", ex);
        }

        return aggregate(request, symbols, outcomes);
    }

    private List<String> resolveSymbols(UniverseBacktestRequest request) {
        if (request.symbols() != null && !request.symbols().isEmpty()) {
            return request.symbols();
        }
        int top = request.topSymbols() != null ? request.topSymbols() : 20;
        return symbolUniverse.topByLiquidity(top);
    }

    private UniverseBacktestReport aggregate(UniverseBacktestRequest request,
                                             List<String> requested,
                                             List<Outcome> outcomes) {
        List<UniverseBacktestReport.SymbolRow> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        int trades = 0, wins = 0, losses = 0, stops = 0, targets = 0, signals = 0, openAtEnd = 0;
        double winSum = 0, lossSum = 0, total = 0, barsHeldWeighted = 0, totalR = 0;
        int symbolsWithTrades = 0, symbolsProfitable = 0;
        int longTrades = 0, shortTrades = 0;
        double longPnl = 0, shortPnl = 0, holdSum = 0;
        int holdCount = 0;
        List<Double> symbolPnls = new ArrayList<>();

        for (Outcome o : outcomes) {
            if (o.report() == null) {
                failures.add(o.symbol() + ": " + o.error());
                continue;
            }
            BacktestReport.Summary s = o.report().overall();
            rows.add(new UniverseBacktestReport.SymbolRow(
                    o.symbol(), s.trades(), s.wins(), s.winRatePct(), s.grossPnlPct(),
                    s.expectancyR(), s.avgBarsHeld(), s.buyAndHoldPct()));

            holdSum += s.buyAndHoldPct();
            holdCount++;

            if (s.trades() == 0) {
                continue;
            }
            symbolsWithTrades++;
            symbolPnls.add(s.grossPnlPct());
            if (s.grossPnlPct() > 0) {
                symbolsProfitable++;
            }
            trades += s.trades();
            wins += s.wins();
            losses += s.losses();
            stops += s.stopExits();
            targets += s.targetExits();
            signals += s.signalExits();
            openAtEnd += s.openAtEnd();
            total += s.grossPnlPct();
            totalR += s.totalR();
            winSum += s.avgWinPct() * s.wins();
            lossSum += s.avgLossPct() * s.losses();
            barsHeldWeighted += s.avgBarsHeld() * s.trades();
            longTrades += s.longTrades();
            shortTrades += s.shortTrades();
            longPnl += s.longPnlPct();
            shortPnl += s.shortPnlPct();
        }

        rows.sort(Comparator.comparingInt(UniverseBacktestReport.SymbolRow::trades).reversed());

        // Median rather than sum: one runaway symbol should not decide the verdict.
        Collections.sort(symbolPnls);
        double median = symbolPnls.isEmpty() ? 0 : symbolPnls.size() % 2 == 1
                ? symbolPnls.get(symbolPnls.size() / 2)
                : (symbolPnls.get(symbolPnls.size() / 2 - 1) + symbolPnls.get(symbolPnls.size() / 2)) / 2;

        double grossExpectancy = trades == 0 ? 0 : total / trades;
        // Fees are charged per side, so a round trip costs twice the configured rate.
        double feePct = request.feePct() != null
                ? request.feePct()
                : BacktestEngine.Params.defaults().feePct();
        double avgHold = holdCount == 0 ? 0 : holdSum / holdCount;

        UniverseBacktestReport.Aggregate aggregate = new UniverseBacktestReport.Aggregate(
                trades, wins, losses,
                trades == 0 ? 0 : 100.0 * wins / trades,
                wins == 0 ? 0 : winSum / wins,
                losses == 0 ? 0 : lossSum / losses,
                total,
                grossExpectancy,
                trades == 0 ? 0 : totalR / trades,
                grossExpectancy - 2 * feePct,
                lossSum == 0 ? Double.POSITIVE_INFINITY : winSum / lossSum,
                stops, targets, signals, openAtEnd,
                trades == 0 ? 0 : barsHeldWeighted / trades,
                symbolsWithTrades, symbolsProfitable, median,
                longTrades, longPnl, shortTrades, shortPnl,
                avgHold,
                (symbolsWithTrades == 0 ? 0 : total / symbolsWithTrades) - avgHold);

        return new UniverseBacktestReport(
                request.interval(), request.htfInterval(),
                requested.size(), requested.size() - failures.size(), failures.size(),
                aggregate, rows, failures);
    }

    private record Outcome(String symbol, BacktestReport report, String error) {}
}
