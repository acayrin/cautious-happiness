# Overlay Translator — model artifacts

Binaries for the Android app [`acayrin/cautious-happiness`](https://github.com/acayrin/cautious-happiness)
(`main` holds source only; this branch holds the models).

Downloaded at runtime from:

```
https://raw.githubusercontent.com/acayrin/cautious-happiness/models/<dir>/<file>
```

## Translation models (CTranslate2 int8)

`<dir>/` holds `model.bin`, `config.json`, `shared_vocabulary.json`, `source.spm`,
`target.spm`.

### HPLT (preferred)

Newer models trained on HPLT v2.0 + OPUS, CC-BY-4.0, attributed in each `NOTICE`.

| dir | source |
|---|---|
| `hplt-ja-en` | WindyTranslate/translate-windy-hplt-ja-en |
| `hplt-ko-en` | HPLT/translate-ko-en-v2.0-hplt_opus (converted here) |
| `hplt-vi-en` | HPLT/translate-vi-en-v2.0-hplt_opus (converted here) |
| `hplt-hi-en` | HPLT/translate-hi-en-v2.0-hplt_opus (converted here) |

### Helsinki OPUS-MT fallback

Used only for languages HPLT does not cover.

`opus-mt-{zh,fr,es,de,it,pt,nl,pl,sv,tr,id,cs}-en`

`pt` is the converted `Helsinki-NLP/opus-mt-ROMANCE-en` (no pt-en model exists;
Romance is a multilingual pair).

### config.json requirements

Every model here ships `"add_source_eos": true` plus a working
`"decoder_start_token"`. Without them Marian output degenerates into repetition
loops:

- Helsinki and the HPLT ko/vi/hi conversions: `"decoder_start_token": "<pad>"`
  (the HPLT conversions also accept `<s>`).
- `hplt-ja-en`: `"<s>"` — its vocabulary has no `<pad>` token, so `<pad>` cannot
  be used.

## whisper.cpp (audio subtitles)

`whisper/ggml-tiny-q5_1.bin` (fast, default) and `whisper/ggml-base-q5_1.bin`
(accurate), from `ggerganov/whisper.cpp`.

## Licensing

- HPLT models: CC-BY-4.0 — see the `NOTICE` in each `hplt-*` directory.
- Helsinki OPUS-MT: Apache-2.0 (zh-en: CC-BY-4.0).
- whisper.cpp models: MIT.
