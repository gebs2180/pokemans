# analysis

Python workspace for calibration and backtests (see [docs/hld.md](../docs/hld.md) §3.4). Placeholder until M5.

Planned contents:
- Notebooks reading the read-only Postgres views `v_prediction_outcomes`, `v_flv_followup_prices` and `v_provider_comparison`.
- Condition calibration: ±1-grade hit rate (target ≥ 80%) and below-stressed-median rate (target < 5%).
- Liquidation replay of the 2021–2023 decline (FLV at peak covers price 60 days later for ≥ 95% of cards).
- The week 1–2 pre-grading provider comparison.

Stack: pandas, scikit-learn, Jupyter. Connect with a read-only database role only.
