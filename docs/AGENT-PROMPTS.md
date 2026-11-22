# ORBIT prompt design

The prompt text lives in `orbit/core/prompts.py` and in the matching file under
`orbitcli`. These notes describe how to edit it safely.

## Goals

- Be specific about the phone context: the agent sees screens, controls and text.
- Ask for short, machine-readable answers that the parser can trust.
- Keep examples neutral and generic so they do not bias a task.
- Separate planning prompts from screen-action prompts.

## Editing rules

- Change one prompt at a time and re-run the relevant checks.
- Do not remove output markers; the parser depends on them.
- Keep placeholder names aligned with the code that formats the prompt.
- Preserve facts and numbers in examples; rewrite only the wording.

## Prompt groups

| Group | Used for |
|---|---|
| Macro planning | Mapping a task to a stored operation |
| Screen action | Choosing the next tap, type or scroll |
| Classification | Marking screen text as stable or changing |
| Enrichment | Adding meaning to map entries |
| Hand-off | Explaining why a person must finish a step |

Prompts are plain Python strings. They are bundled into the APK when the sync
script copies the agent sources, so changes take effect only after a rebuild.
