# e2e

The end-to-end suite, run against the `xpertss-test` GitHub org and a disposable sample repo.
It becomes a Maven module when the first end-to-end test is written, behind a profile so a plain
`mvn verify` never needs GitHub.

Starts in P4.
