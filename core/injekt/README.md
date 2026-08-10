# :core:injekt

Vendored copy of [null2264/injekt-koin](https://github.com/null2264/injekt-koin), commit
`aad18b6148`, MIT licensed — see [LICENSE](LICENSE), which carries both copyright holders.

## What it is

The `uy.kohesive.injekt` API backed by Koin. [Injekt] itself is unmaintained, and the app calls
`Injekt.get()` and `injectLazy()` from over two hundred files; this keeps that API alive without
rewriting any of them. Koin does the actual resolving, `KoinRegistrar` is the adapter between the
two, and no Koin type appears in the public API.

## Why it lives here instead of being a dependency

It used to be pulled from JitPack, which builds it from its author's GitHub repo — so a repo that
went away took the build with it. It is six files and eight kilobytes with no upstream to track
(unlike the subsampling image view, which is a fork of a fork and stays external so it can still
merge from the tachiyomiorg one). Copying it in costs nothing and removes the last piece of build
infrastructure Karasu did not control.

The sources are verbatim. Keep them that way: anything Karasu-specific belongs in the calling code,
not here, so this stays diffable against upstream if it ever needs to be.

[Injekt]: https://github.com/kohesive/injekt
