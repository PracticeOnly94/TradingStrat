package com.tradingstrat.analysis;

import com.tradingstrat.indicator.CipherB;
import com.tradingstrat.indicator.Ta;
import com.tradingstrat.model.ChartData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Risk-overlay backtest: hold the asset, but change exposure with the trend.
 *
 * <p>This is the one framing in the project that beat buy-and-hold. Entry signals need
 * precision — ours peaked at 8.8%, so over 91% of "buy" signals were not bottoms. An
 * exposure rule only has to be roughly right about sustained direction, and the payoff is
 * asymmetric: avoiding a −65% year matters more than catching every rally.
 *
 * <p>Three parameters are swept:
 * <ul>
 *   <li><b>EMA length</b> — how slow the trend definition is.</li>
 *   <li><b>Hysteresis band</b> — a dead zone around the line. Without one, price
 *       oscillating around the average forces a switch on every crossing, and each switch
 *       costs a fee; the band makes the rule commit before it acts.</li>
 *   <li><b>Shorting</b> — whether a downtrend means cash or an actual short. Cash caps
 *       losses; shorting is what turns a bear market into a source of return.</li>
 * </ul>
 *
 * <p>Exposure at bar {@code i} is decided from data up to {@code i} and applied to the
 * return from {@code i} to {@code i+1}, so no decision uses a price it could not see.
 */
@Component
public class ExposureBacktester {

    /**
     * @param emaLen    trend EMA length
     * @param bandPct   hysteresis half-width, as a percentage of the EMA
     * @param allowShort whether a downtrend is shorted rather than sat out in cash
     */
    public record Config(int emaLen, double bandPct, boolean allowShort) {
        public String label() {
            return String.format("ema%d band%.1f%% %s", emaLen, bandPct, allowShort ? "L/S" : "long");
        }
    }

    /**
     * @param totalReturnPct compounded return over the whole window
     * @param maxDrawdownPct worst peak-to-trough fall of the equity curve
     * @param timeLongPct    share of bars held long
     * @param timeShortPct   share of bars held short
     * @param switches       exposure changes, each charged a fee
     */
    public record Result(
            double totalReturnPct,
            double maxDrawdownPct,
            double timeLongPct,
            double timeShortPct,
            int switches
    ) {}

    /** The sweep: EMA lengths x hysteresis bands x long-only/long-short, plus HOLD. */
    public List<Config> sweep() {
        List<Config> configs = new ArrayList<>();
        for (int emaLen : new int[]{50, 100, 150, 200, 300}) {
            for (double band : new double[]{0.0, 1.0, 2.0, 3.0}) {
                configs.add(new Config(emaLen, band, false));
                configs.add(new Config(emaLen, band, true));
            }
        }
        return configs;
    }

    /** Run buy-and-hold plus every swept config over one symbol. */
    public Map<String, Result> run(ChartData chart, double feePct) {
        CipherB.Series s = CipherB.computeSeries(chart.candles());
        double[] close = s.close();

        Map<String, Result> out = new LinkedHashMap<>();
        out.put("HOLD", simulate(close, null, 0, false, feePct));

        for (Config c : sweep()) {
            double[] ema = Ta.ema(close, c.emaLen());
            out.put(c.label(), simulate(close, ema, c.bandPct(), c.allowShort(), feePct));
        }
        return out;
    }

    /** {@code ema == null} means always fully invested — the benchmark. */
    private Result simulate(double[] close, double[] ema, double bandPct,
                            boolean allowShort, double feePct) {
        int n = close.length;
        double equity = 1.0;
        double peak = 1.0;
        double maxDrawdown = 0;
        int position = ema == null ? 1 : 0;
        int switches = 0;
        int barsLong = 0;
        int barsShort = 0;
        int bars = 0;

        for (int i = 320; i < n - 1; i++) {
            if (ema != null) {
                if (Double.isNaN(ema[i])) {
                    continue;
                }
                double upper = ema[i] * (1 + bandPct / 100.0);
                double lower = ema[i] * (1 - bandPct / 100.0);

                // Inside the band the previous position is kept: that dead zone is the
                // whole point of hysteresis.
                int desired = position;
                if (close[i] > upper) {
                    desired = 1;
                } else if (close[i] < lower) {
                    desired = allowShort ? -1 : 0;
                }

                if (desired != position) {
                    // Flipping long to short crosses two units of exposure, so two fees.
                    equity *= 1 - (feePct / 100.0) * Math.abs(desired - position);
                    position = desired;
                    switches++;
                }
            }

            double ret = (close[i + 1] - close[i]) / close[i];
            equity *= 1 + position * ret;
            if (equity < 1e-9) {
                equity = 1e-9;      // wiped out; keep the curve finite
            }

            if (position > 0) {
                barsLong++;
            } else if (position < 0) {
                barsShort++;
            }
            bars++;

            peak = Math.max(peak, equity);
            maxDrawdown = Math.max(maxDrawdown, (peak - equity) / peak);
        }

        return new Result(
                (equity - 1) * 100.0,
                maxDrawdown * 100.0,
                bars == 0 ? 0 : 100.0 * barsLong / bars,
                bars == 0 ? 0 : 100.0 * barsShort / bars,
                switches);
    }
}
