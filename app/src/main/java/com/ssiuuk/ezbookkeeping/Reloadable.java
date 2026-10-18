package com.ssiuuk.ezbookkeeping;

/** A screen that reloads its data when it changed since it was last shown. */
interface Reloadable {
    void reloadIfStale();
}
