# Fog experiments at 9 steps

Each row is fog.mat as of c4576ed with one change (see EXPERIMENTS in tools/mali/analyze.py), compiled at 9 steps, the count used by HIGH with Best looking. `applied` is what went into fog.mat.

| experiment | Mali-G52 | Mali-G57 | Mali-G78 | Mali-G715 | Immortalis-G925 | 16-bit maths (Mali-G52) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| baseline | 24.33 | 20.88 | 20.88 | 6.89 | 6.45 | 12% |
| one-exp | 24.33 | 20.25 | 20.25 | 6.65 | 6.32 | 58% |
| one-divide | 24.67 | 20.25 | 20.25 | 6.73 | 6.33 | 11% |
| highp-time | 24.33 | 20.88 | 20.88 | 6.89 | 6.45 | 12% |
| cpu-scroll | 24.33 | 20.88 | 20.88 | 6.89 | 6.45 | 12% |
| two-reads | 23.83 | 20.25 | 20.25 | 6.66 | 6.26 | 12% |
| all-four | 22.67 | 19.12 | 19.12 | 6.34 | 5.97 | 58% |
| no-divide | 23.83 | 19.75 | 19.75 | 6.46 | 6.09 | 58% |
| applied | 22.67 | 19.12 | 19.12 | 6.34 | 5.97 | 58% |
