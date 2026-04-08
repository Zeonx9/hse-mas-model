#!/usr/bin/env python3
"""
BehaviorSpace-like experiment runner for the Museum Complex Jason model.

Reads experiment.json, generates all parameter combinations,
runs the MAS for each, and collects results into a CSV file.

Usage:
    python3 experiments/run_experiments.py                          # default config
    python3 experiments/run_experiments.py experiments/my_config.json  # custom config
"""

import json
import itertools
import subprocess
import sys
import os
import time
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent.parent
MAS2J_PATH = PROJECT_DIR / "museum.mas2j"
DEFAULT_CONFIG = PROJECT_DIR / "experiments" / "experiment.json"

MAS2J_TEMPLATE = """\
MAS museum_complex {{

    environment: museum.MuseumEnv({museumCapacity}, {hotelCapacity}, {ticketPrice}, {hotelPrice}, {monthlyExpenditures}, {maxDays}, "{csvFile}", {numVisitors})

    agents: visitor #{numVisitors};
            manager;
            restorer;

    aslSourcePath: "src/agt";
}}
"""


def load_config(path: str) -> dict:
    with open(path) as f:
        return json.load(f)


def generate_combinations(config: dict) -> list[dict]:
    params = config["parameters"]
    keys = list(params.keys())
    values = [params[k] for k in keys]
    combos = []
    for combo in itertools.product(*values):
        combos.append(dict(zip(keys, combo)))
    return combos


def write_mas2j(combo: dict, max_days: int, csv_file: str, num_visitors: int):
    content = MAS2J_TEMPLATE.format(
        museumCapacity=combo["museumCapacity"],
        hotelCapacity=combo["hotelCapacity"],
        ticketPrice=combo["ticketPrice"],
        hotelPrice=combo["hotelPrice"],
        monthlyExpenditures=combo["monthlyExpenditures"],
        maxDays=max_days,
        csvFile=csv_file,
        numVisitors=num_visitors,
    )
    MAS2J_PATH.write_text(content)


def run_mas() -> bool:
    result = subprocess.run(
        ["gradle", "run", "-q"],
        cwd=str(PROJECT_DIR),
        capture_output=True,
        text=True,
        timeout=600,
    )
    return result.returncode == 0


def main():
    config_path = sys.argv[1] if len(sys.argv) > 1 else str(DEFAULT_CONFIG)
    config = load_config(config_path)

    max_days = config["maxDays"]
    runs_per = config.get("runsPerCombination", 1)
    csv_file = config["output"]
    num_visitors = config.get("numVisitors", 65)

    csv_abs = str((PROJECT_DIR / csv_file).resolve())

    original_mas2j = MAS2J_PATH.read_text() if MAS2J_PATH.exists() else None

    csv_path = PROJECT_DIR / csv_file
    if csv_path.exists():
        csv_path.unlink()
        print(f"Removed old CSV: {csv_file}")

    combos = generate_combinations(config)
    total_runs = len(combos) * runs_per
    print(f"Experiment config: {config_path}")
    print(f"  {len(combos)} parameter combinations x {runs_per} runs = {total_runs} total experiments")
    print(f"  maxDays = {max_days}, numVisitors = {num_visitors}")
    print(f"  Output: {csv_file}")
    print()

    completed = 0
    failed = 0
    start_time = time.time()

    for i, combo in enumerate(combos):
        for run_idx in range(runs_per):
            completed += 1
            label = ", ".join(f"{k}={v}" for k, v in combo.items())
            print(f"[{completed}/{total_runs}] run={run_idx+1}/{runs_per} | {label}", end=" ... ", flush=True)

            write_mas2j(combo, max_days, csv_abs, num_visitors)

            run_start = time.time()
            try:
                ok = run_mas()
            except subprocess.TimeoutExpired:
                ok = False
            run_elapsed = time.time() - run_start

            if ok:
                print(f"OK ({run_elapsed:.1f}s)")
            else:
                failed += 1
                print(f"FAILED ({run_elapsed:.1f}s)")

    elapsed = time.time() - start_time

    if original_mas2j is not None:
        MAS2J_PATH.write_text(original_mas2j)
        print(f"\nRestored original museum.mas2j")

    print(f"\n{'='*50}")
    print(f"Experiments complete: {completed - failed}/{total_runs} succeeded, {failed} failed")
    print(f"Total time: {elapsed:.1f}s ({elapsed/total_runs:.1f}s per run)")
    print(f"Results: {csv_file}")
    print(f"{'='*50}")


if __name__ == "__main__":
    main()
