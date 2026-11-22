# ORBIT release gate

A capability is production-ready only when every applicable check has evidence.

| Property | Evidence | Threshold |
| --- | --- | --- |
| Reliability | repeated task suite in CI | >= 95% success over 20 runs |
| Security | adversarial prompt/tool tests | zero unauthorised actions |
| Approval integrity | parameter-tamper and replay tests | 100% rejected |
| Recovery | interrupted-workflow resume tests | zero duplicated side effects |
| Idempotency | retry tests on send/pay/create | zero duplicates |
| Verification | read-back/state comparison | 100% of critical actions verified |
| Privacy | disclosure report + hard-delete test | no residual vectors |
| Transparency | audit trail coverage | every action has an audit event |
| Extensibility | skill install/revoke test | signed manifest enforced |
| Portability | model/provider swap test | no core changes needed |
| Cost control | per-task budget test | every delegated task bounded |
| Responsiveness | local-routing test | low-risk tasks skip cloud reasoning |

Run before release:

```
python -m pytest tests/test_orbit_core*.py -q
python -m ruff check orbit_core tests/test_orbit_core*.py
python -m orbit_core.cli --demo
```

Device-only properties (Android keystore/IPC, voice, camera, live install) are
explicitly **not** satisfied by local tests and remain blocking for a phone release.
