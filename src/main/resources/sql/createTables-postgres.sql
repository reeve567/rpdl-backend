CREATE TABLE IF NOT EXISTS f95zone
(
    id          INTEGER PRIMARY KEY,
    tags        TEXT,
    rating      TEXT,
    description TEXT
);

CREATE TABLE IF NOT EXISTS uploaders
(
    id       INTEGER PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    name     TEXT NOT NULL UNIQUE,
    lastSeen TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS categories
(
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS rpdlInstances
(
    id         INTEGER PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    threadID   INTEGER NOT NULL DEFAULT -1,
    title      TEXT    NOT NULL,
    version    TEXT,
    fileSize   TEXT    NOT NULL,
    categoryID INTEGER NOT NULL,
    torrentID  INTEGER NOT NULL UNIQUE,
    uploadDate TEXT    NOT NULL,
    uploaderID INTEGER NOT NULL,
    links      TEXT,
    CONSTRAINT threadID
        FOREIGN KEY (threadID) REFERENCES f95zone (id),
    CONSTRAINT categoryID
        FOREIGN KEY (categoryID) REFERENCES categories (id),
    CONSTRAINT uploaderID
        FOREIGN KEY (uploaderID) REFERENCES uploaders (id)
);

CREATE TABLE IF NOT EXISTS data
(
    name  TEXT PRIMARY KEY,
    value TEXT
);

INSERT INTO data (name, value)
VALUES ('lastUpdate', '0');

CREATE INDEX IF NOT EXISTS rpdlInstances_threadID ON rpdlInstances (threadID);
CREATE INDEX IF NOT EXISTS rpdlInstances_categoryID ON rpdlInstances (categoryID);
CREATE INDEX IF NOT EXISTS rpdlInstances_uploaderID ON rpdlInstances (uploaderID);

REINDEX INDEX rpdlInstances_threadID;