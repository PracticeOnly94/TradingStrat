package com.tradingstrat.controller;

import com.tradingstrat.analysis.CombinationReport;
import com.tradingstrat.analysis.ConditionReport;
import com.tradingstrat.model.CombinationSearchRequest;
import com.tradingstrat.service.CombinationSearchService;
import com.tradingstrat.service.ExposureService;
import com.tradingstrat.analysis.ExtremeProfileReport;
import com.tradingstrat.service.ConditionAnalysisService;
import com.tradingstrat.backtest.BacktestReport;
import com.tradingstrat.backtest.UniverseBacktestReport;
import com.tradingstrat.model.ExtremeProfileRequest;
import com.tradingstrat.service.ExtremeAnalysisService;
import com.tradingstrat.model.AnalysisOutcome;
import com.tradingstrat.model.AnalysisRequest;
import com.tradingstrat.model.AnalysisResult;
import com.tradingstrat.model.BacktestRequest;
import com.tradingstrat.model.UniverseBacktestRequest;
import com.tradingstrat.service.BacktestService;
import com.tradingstrat.service.UniverseBacktestService;
import com.tradingstrat.service.ChartAnalysisService;
import com.tradingstrat.service.ParallelAnalysisService;
import com.tradingstrat.service.SequentialAnalysisService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final ChartAnalysisService chartAnalysisService;
    private final SequentialAnalysisService sequentialAnalysisService;
    private final ParallelAnalysisService parallelAnalysisService;
    private final BacktestService backtestService;
    private final UniverseBacktestService universeBacktestService;
    private final ExtremeAnalysisService extremeAnalysisService;
    private final ConditionAnalysisService conditionAnalysisService;
    private final ExposureService exposureService;
    private final com.tradingstrat.live.LiveExecutor liveExecutor;
    private final CombinationSearchService combinationSearchService;

    public AnalysisController(
            ChartAnalysisService chartAnalysisService,
            SequentialAnalysisService sequentialAnalysisService,
            ParallelAnalysisService parallelAnalysisService,
            BacktestService backtestService,
            UniverseBacktestService universeBacktestService,
            ExtremeAnalysisService extremeAnalysisService,
            ConditionAnalysisService conditionAnalysisService,
            CombinationSearchService combinationSearchService,
            ExposureService exposureService,
            com.tradingstrat.live.LiveExecutor liveExecutor) {
        this.liveExecutor = liveExecutor;
        this.exposureService = exposureService;
        this.combinationSearchService = combinationSearchService;
        this.extremeAnalysisService = extremeAnalysisService;
        this.conditionAnalysisService = conditionAnalysisService;
        this.chartAnalysisService = chartAnalysisService;
        this.sequentialAnalysisService = sequentialAnalysisService;
        this.parallelAnalysisService = parallelAnalysisService;
        this.backtestService = backtestService;
        this.universeBacktestService = universeBacktestService;
    }

    /**
     * Analyse a single symbol across one or more timeframes.
     *
     * Single timeframe:     { "symbol": "BTCUSDT", "intervals": ["1h"] }
     * Multi-timeframe:      { "symbol": "BTCUSDT", "intervals": ["1h", "4h"] }
     */
    @PostMapping("/single")
    public AnalysisResult single(@Valid @RequestBody AnalysisRequest request) {
        return chartAnalysisService.analyze(request);
    }

    /**
     * Analyse multiple symbols sequentially, each potentially across multiple timeframes.
     *
     * [
     *   { "symbol": "BTCUSDT", "intervals": ["1h", "4h"] },
     *   { "symbol": "ETHUSDT", "intervals": ["15m", "1h", "4h"] }
     * ]
     */
    @PostMapping("/sequential")
    public List<AnalysisOutcome> sequential(@Valid @RequestBody List<AnalysisRequest> requests) {
        return sequentialAnalysisService.analyzeAll(requests);
    }

    /**
     * Backtest the active strategy over historical candles.
     *
     * { "symbol": "BTCUSDT", "interval": "1h", "htfInterval": "4h", "bars": 3000 }
     */
    @PostMapping("/backtest")
    public BacktestReport backtest(@Valid @RequestBody BacktestRequest request) {
        return backtestService.run(request);
    }

    /**
     * Profile what indicators look like at real tops and bottoms, versus everywhere else.
     *
     * { "topSymbols": 20, "interval": "1h", "bars": 5000, "minMoveAtr": 3.0 }
     */
    @PostMapping("/extremes")
    public ExtremeProfileReport extremes(@Valid @RequestBody ExtremeProfileRequest request) {
        return extremeAnalysisService.run(request);
    }

    /**
     * The inverse: forward returns given each condition, against the all-bars baseline.
     * This is the version that bears on trading.
     */
    @PostMapping("/conditions")
    public ConditionReport conditions(@Valid @RequestBody ExtremeProfileRequest request) {
        return conditionAnalysisService.run(request);
    }

    /**
     * What the live overlay rule says right now for each configured symbol.
     * Read-only: places no orders and changes no state, whatever the live settings are.
     */
    @PostMapping("/live/preview")
    public List<com.tradingstrat.live.LiveExecutor.Decision> livePreview() {
        return liveExecutor.preview();
    }

    /**
     * Risk-overlay backtest: hold, but stand aside in hostile conditions, measured
     * against buy-and-hold on both return and drawdown.
     */
    @PostMapping("/exposure")
    public java.util.Map<String, Object> exposure(@Valid @RequestBody ExtremeProfileRequest request) {
        return exposureService.run(request);
    }

    /**
     * Exhaustive combination search under a strict search/holdout split, per regime.
     * Only combinations with holdoutPassed=true mean anything.
     */
    @PostMapping("/combinations")
    public CombinationReport combinations(@Valid @RequestBody CombinationSearchRequest request) {
        return combinationSearchService.run(request);
    }

    /**
     * Backtest across many symbols at once, for a sample large enough to be meaningful.
     *
     * { "topSymbols": 20, "interval": "1h", "htfInterval": "4h", "bars": 5000 }
     */
    @PostMapping("/backtest/universe")
    public UniverseBacktestReport backtestUniverse(
            @Valid @RequestBody UniverseBacktestRequest request) {
        return universeBacktestService.run(request);
    }

    /**
     * Same as /sequential but each symbol is processed concurrently via virtual threads.
     */
    @PostMapping("/parallel")
    public List<AnalysisOutcome> parallel(@Valid @RequestBody List<AnalysisRequest> requests) {
        return parallelAnalysisService.analyzeAll(requests);
    }
}
