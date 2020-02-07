## Description

An assortment of Clojure CLIs I rely on that have a fast startup time.

## Setup

Setup your $PATH:
```sh
$ git clone https://github.com/cldwalker/clj-clis
$ cd clj-clis

$ export PATH=$PATH:$PWD
$ export $BABASHKA_CLASSPATH=$PWD/src
# To last outside this shell, put the above in your rc file, replacing $PWD
# with the path to this repo
```

Then [install babashka](https://github.com/borkdude/babashka#installation).

## CLIs

### clj-github-pr-for-commit

Prints github url of PR associated with a commit. It assumes a current directory's repository but any repository can be specified. See https://github.com/mislav/hub-api-utils/blob/master/bin/hub-pr-with-commit for an alternative implementation.

#### Setup

* Install [clj-http-lite](https://github.com/borkdude/babashka#clj-http-lite) but with a slight tweak:
  ```sh
  CLJ_HTTP_LITE_PATH="$(clojure -Sdeps '{:deps {clj-http-lite {:git/url "https://github.com/borkdude/clj-http-lite" :sha "f44ebe45446f0f44f2b73761d102af3da6d0a13e"}}}' -Spath)"
  # This assumes you have followed the setup above
  export BABASHKA_CLASSPATH=$BABASHKA_CLASSPATH:$CLJ_HTTP_LITE_PATH
  ```
* Optional: To have this executable with private repositories, set `$GITHUB_USER` to your user and [create and set a $GITHUB_OAUTH_TOKEN](https://developer.github.com/apps/building-oauth-apps/authorizing-oauth-apps/#non-web-application-flow)

#### Usage

```sh
# Prints url and opens url for osx
$ ./clj-github-pr-for-commit -r atom/atom 0f521f1e8afbcaf73479ea93dd4c87d9187903cb
"https://github.com/atom/atom/pull/20350"

# Open url of current github repository
$ ./clj-github-pr-for-commit SHA
```

### clj-github-repo

For the current github repo, open different repo urls e.g. commit or branch. Inspired by [this ruby version](https://github.com/cldwalker/irbfiles/blob/1fb97d84bcdf491325176d08e386468b12ece738/boson/commands/public/url/github.rb#L20-L50).

To open a commit, `clj-github-repo -c SHA`.

### clj-grep-result-frequencies

For use with grep command to group results by frequency e.g. `git grep protocol | clj-group-grep-results`

## Additional Links

* See https://github.com/borkdude/babashka#gallery for additional ideas
