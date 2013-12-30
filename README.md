# tree

A Clojure port of the AVL trees in Mulgara.

Mulgara uses single objects with locking in order to manage trees, but this will
attempt to avoid locking and stick to immutable data structures. One effect of this
is that ownership of the file has to move out of the tree, since the same file needs
to be shared by various incarnations of the structures. This makes some of Mulgara's
JVM Voodoo to minimize memory usage ineffective, though some of it has survived here -
probably with no benefit.

Currently mapping block files, and reading/writing blocks. This will be expanded to
AVLFile and AVLNode next.

## Usage

None yet. This version will not have free lists, and will be a write-once tree.

## License

Copyright © 2013 Paul Gearon

Distributed under the Eclipse Public License, the same as Clojure.
