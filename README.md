## Description

An assortment of handy [Babashka](https://github.com/borkdude/babashka) CLIs:
scripts and tasks. scripts strive to be compatible with Clojure.

## Prerequisites

[Babashka](https://github.com/borkdude/babashka#installation) >= 0.7.2 is required.

## Tasks

bb.edn contains global tasks i.e. tasks that are useful in any directory or
project. To run these tasks from any directory, clone this repo and then use a
shell function to reference the cloned directory:

```sh
function bbg() { BABASHKA_EDN=/path/to/this-repo/bb.edn bb --config
/path/to/this-repo/bb.edn $@ }
```

Run `bbg tasks` to see all the available tasks.

## Scripts

### Setup

Scripts/commands/executables are located in `bin/`. To use an individual script, simply copy
and use it:

```sh
$ curl -o bb-github-repo https://raw.githubusercontent.com/cldwalker/bb-clis/master/bin/bb-github-repo
$ chmod +x bb-github-repo
$ ./bb-github-repo -h
```

If you want to use all scripts in this repo, then put `bin/` on `$PATH`:

```sh
$ git clone https://github.com/cldwalker/bb-clis
$ export PATH=$PATH:$HOME/path/to/bb-clis/bin
```

### Usage

See [scripts.md](doc/scripts.md) which provides useful examples of several scripts.

## Development

Code is organized as follows:
* `src/cldwalker/bb-clis/tasks/` - Namespaces that are mainly run within babashka tasks
* `src/cldwalker/bb-clis/cli/` - Namespaces that useful to scripts and possibly tasks.
* `src/cldwalker/bb-clis/util/` - Namespaces that are useful to any clojure or bb program, not just CLIs.

## Misc bb tips

### Preloads

Babashka supports `$BABASHA_PRELOADS` which allows arbitrary clojure to be run at the start of each invocation. This is handy for loading one's preferred set of vars and namespaces, especially when paired with an alias. For example, `alias bbp="BABASHKA_PRELOADS='(load-file (str (System/getenv \"HOME\") \"/path/to/this-repo/preloads.clj\"))' bb"`

Preloaded fns like `pprint` are then available on the commandline:

```sh
bbp '(->> (System/getenv) (into {}) pprint)'
```
## License
See LICENSE.md

## Additional Links

* For more bb setup and aliases, see [my dotfiles repo](https://github.com/cldwalker/dotfiles/search?q=bb&unscoped_q=bb)
* See https://github.com/borkdude/babashka/blob/master/doc/examples.md for additional babashka cmd examples
