## Description

An assortment of handy [Babashka](https://github.com/borkdude/babashka) CLIs:
scripts and tasks. scripts strive to be compatible with Clojure.

## Prerequisites

[Babashka](https://github.com/borkdude/babashka#installation) >= 1.12.218 is required.

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

Scripts are located in `src/cldwalker/bb_clis/bin/`. To install a script as a CLI, install [bbin](https://github.com/babashka/bbin) and then use it:

```sh
# For example, install logseq-graph-stats
$ bbin install https://github.com/cldwalker/bb-clis.git --as logseq-graph-stats --main-opts '["-m" "cldwalker.bb-clis.bin.logseq-graph-stats"]'
# Confirm that the CLI is installed
$ bbin ls
```

To install a different CLI, use different `--as` and `--main-opts`. If you're unsure of what to use for those values, see [the :bbin/bin key in bb.edn](https://github.com/cldwalker/bb-clis/blob/140c8f5f45b126b341ae4b8b0457aca1629bec17/bb.edn#L7-L31).

If you want to install all scripts in this repo:

```sh
$ git clone https://github.com/cldwalker/bb-clis
$ bb bbin:install
```

### Usage

See [scripts.md](doc/scripts.md) which provides useful examples of several scripts.

## Development

Code is organized as follows:
* `src/cldwalker/bb-clis/tasks/` - Namespaces that are mainly run within babashka tasks
* `src/cldwalker/bb-clis/bin/` - Namespaces that each represent a separate CLI
* `src/cldwalker/bb-clis/cli/` - Namespaces that are useful utils to CLI
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
