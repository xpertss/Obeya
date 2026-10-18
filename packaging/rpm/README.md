# packaging/rpm

The RPM packaging for the Obeya package set — `obeya-common`, `obeya-cli`, `obeya-server`,
`obeya-server-image`, `obeya-runner` (plus the optional `obeya` meta) — and `obeya-setup`,
the per-role installer.

The signed RPMs and `createrepo` metadata are published as a DNF repository on GitHub Pages
(`https://xpertss.github.io/Obeya/`). `setup.sh` adds the repo and `dnf install`s the package
set the host's role warrants (§16.4); updates flow through `dnf`.

Starts in P7.
