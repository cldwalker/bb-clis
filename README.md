## Description

An assortment of Clojure CLIs I rely on that have a fast startup time.

## Setup

Setup your $PATH:
```sh
$ git clone https://github.com/cldwalker/clj-clis
$ cd clj-clis
# To try in a shell
$ export PATH=$PATH:$PWD
# To install longer term, add above to your rc file substituting $PWD with full path
```

Then [install babashka](https://github.com/borkdude/babashka#installation).

## CLIs

### clj-github-pr-for-commit

Prints github url of PR associated with a commit. It assumes a current directory's repository but any repository can be specified. See https://github.com/mislav/hub-api-utils/blob/master/bin/hub-pr-with-commit for an alternative implementation.

#### Setup

* [Install clj-http-lite](https://github.com/borkdude/babashka#clj-http-lite) for babashka
* Optional: To have this executable with private repositories, set `$GITHUB_USER` to your user and [create and set a $GITHUB_OAUTH_TOKEN](https://developer.github.com/apps/building-oauth-apps/authorizing-oauth-apps/#non-web-application-flow)

#### Usage

```sh
# Print url of an atom commit
$ ./clj-github-pr-for-commit -r atom/atom 0f521f1e8afbcaf73479ea93dd4c87d9187903cb
"https://github.com/atom/atom/pull/20350"

# On osx, open url
$ ./clj-github-pr-for-commit -r atom/atom 0f521f1e8afbcaf73479ea93dd4c87d9187903cb |xargs open

# Open url of current github repository
$ ./clj-github-pr-for-commit SHA |xargs open
```

### clj-grep-result-frequencies

For use with grep command to group results by frequency e.g. `git grep protocol | clj-group-grep-results`

## Additional Links

* See https://github.com/borkdude/babashka#gallery for additional ideas
