# Cycles per pixel, shipped materials

Longest path through each material's main pixel shader, in cycles of its busiest pipeline. Fog's loop is counted at its cap of 24 steps; see fog_steps.md for the step counts the app really uses.

| material | Mali-G52 | Mali-G57 | Mali-G78 | Mali-G715 | Immortalis-G925 | 16-bit maths (Mali-G52) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| dust | 2.37 | 2.06 | 2.06 | 0.68 | 0.65 | 24% |
| fog | 57.17 | 48.25 | 48.25 | 17.05 | 16.11 | 32% |
| neon | 0.5 | 0.25 | 0.25 | 0.12 | 0.12 | 50% |
| polished_steel (DYN) | 12.53* | 8.31* | 8.31* | 7.0* | 7.0* | 54% |
| polished_steel (DYN|SRE) | 19.5* | 16.0* | 16.0* | 16.0* | 16.0* | 37% |
| vinyl (DYN) | 15.63* | 11.19* | 11.19* | 7.0* | 7.0* | 62% |
| vinyl (DYN|SRE) | 23.0* | 16.0* | 16.0* | 16.0* | 16.0* | 45% |

\* No longest path (Filament loops over the scene's lights): every instruction counted once, which is about one light.
