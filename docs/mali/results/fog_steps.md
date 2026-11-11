# Fog: cycles per pixel by step count

fog.mat compiled with its step count fixed. LOW uses 0 steps, MEDIUM and HIGH 7, HIGH with Best looking 9.

| steps | Mali-G52 | Mali-G57 | Mali-G78 | Mali-G715 | Immortalis-G925 | 16-bit maths (Mali-G52) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 0.12 | 0.06 | 0.06 | 0.03 | 0.02 | – |
| 1 | 4.08 | 3.5 | 3.5 | 1.1 | 1.05 | 12% |
| 7 | 18.17 | 15.25 | 15.25 | 5.01 | 4.73 | 58% |
| 9 | 22.67 | 19.12 | 19.12 | 6.34 | 5.97 | 58% |
| 14 | 34.17 | 28.88 | 28.88 | 9.64 | 9.07 | 58% |
| 24 | 57.17 | 48.25 | 48.25 | 16.12 | 15.18 | 58% |
