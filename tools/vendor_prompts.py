#!/usr/bin/env python3
"""Copy the source prompt texts into orbit/core/prompts.py."""
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
HEADER = '''"""Prompt texts used by the agent.

Copied from the source ``orbitcli/prompts.py`` so this package is
self-contained: the APK ships only ``orbit``, so importing the source
tree at runtime is not an option.

Regenerate with:  python3 tools/vendor_prompts.py
"""

'''
(REPO / "orbit/core/prompts.py").write_text(
    HEADER + (REPO / "orbitcli/prompts.py").read_text()
)
print("vendored prompts")
