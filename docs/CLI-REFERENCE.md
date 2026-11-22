# ORBIT CLI reference

This is a short reference for the most common flags. Run any command with `--help`
to see the current options.

## Map validation

`python -m orbit.check` reads map YAML and reports broken references. Point it at
a directory or a single file. It performs no device access.

`python -m orbit.validate` adds structural and privacy checks. Use it before a map
leaves the machine.

## Device probe

`python -m orbit.probe` checks whether a device is reachable and which control
channel is available. Use it after changing a cable, ROM or pairing setup.

## Task run

`python -m orbit.run` accepts a task string and an app map, then prints the actions
and the final answer. This is the host-side counterpart of the app flow.

## Configuration

Model settings come from files in `configs/`. A typical entry names the provider,
model id and the XML or screen-description format. Keep real keys out of these
files; use environment variables or local, ignored configuration.
