# analysis

Python workspace for calibration and backtests (see [docs/hld.md](../docs/hld.md) §3.4). Not built yet; M5. The one script so far is the PokeTrace pilot puller in `poketrace/` ([poketrace-pilot.md](../docs/poketrace-pilot.md)).

Planned contents:
- Notebooks reading the read-only Postgres views. Planned: `v_prediction_outcomes`, `v_flv_followup_prices` and `v_provider_comparison`, none built. Built so far: only `v_offer_outcomes` (V15, rewritten in V16, V18 and V20). `v_offer_grade_calibration` was dropped in V16.
- No read-only database role exists yet.
- Condition calibration: ±1-grade hit rate (target ≥ 80%) and below-stressed-median rate (target < 5%).
- Liquidation replay of the 2021–2023 decline (FLV at peak covers price 60 days later for ≥ 95% of cards).
- The week 1–2 pre-grading provider comparison.

Stack: pandas, scikit-learn, Jupyter. Connect with a read-only database role only.
