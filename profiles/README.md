# Profiles

Put the binary device profiles you extracted from **your own** ARCore
installation here (how they work: `docs/04-profiles.md`). Profile files are
Google's proprietary data and are intentionally not part of this repository —
the server reads them from this directory using the glob patterns in
`profiles.json` (typical names: `profiles/example_pADuJF.pb`,
`profiles/hwmar_nJH8dB.pb`). `*.pb` and `*.zip` are ignored by git.
