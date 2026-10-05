"""SQLite credentials and OAuth grants; plaintext secrets never reach the database."""
import hashlib
import hmac
import json
import secrets
import sqlite3
import time


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


class Store:
    def __init__(self, path, clock=time.time):
        self.clock = clock
        self.db = sqlite3.connect(path)
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY, name TEXT UNIQUE, salt BLOB, password BLOB);
            CREATE TABLE IF NOT EXISTS phones(id TEXT PRIMARY KEY, owner TEXT, name TEXT, actions TEXT);
            CREATE TABLE IF NOT EXISTS tokens(hash TEXT PRIMARY KEY, kind TEXT, owner TEXT, phone TEXT, grant_id TEXT, expires REAL);
            CREATE TABLE IF NOT EXISTS codes(hash TEXT PRIMARY KEY, owner TEXT, redirect TEXT, expires REAL);
            CREATE TABLE IF NOT EXISTS executions(owner TEXT, request TEXT, body_hash TEXT, response TEXT, expires REAL, PRIMARY KEY(owner, request));
        """)
        self.db.commit()

    def create_user(self, name, password):
        if not name or len(name) > 80 or len(password) < 12 or len(password) > 256:
            raise ValueError("Use a username and password of 12–256 characters")
        salt = secrets.token_bytes(16)
        hashed = hashlib.scrypt(password.encode(), salt=salt, n=16384, r=8, p=1)
        owner = secrets.token_hex(16)
        self.db.execute("INSERT INTO users VALUES(?,?,?,?)", (owner, name, salt, hashed))
        self.db.commit()
        return owner

    def verify(self, name, password):
        return self.verify_password(self.credentials(name), password)

    def credentials(self, name):
        return self.db.execute("SELECT id,salt,password FROM users WHERE name=?", (name,)).fetchone()

    @staticmethod
    def verify_password(row, password):
        salt = row[1] if row else b"missing-account!"
        hashed = hashlib.scrypt(password.encode(), salt=salt, n=16384, r=8, p=1)
        return row[0] if row and hmac.compare_digest(hashed, row[2]) else None

    def phone(self, phone_id, owner, name):
        row = self.db.execute("SELECT owner FROM phones WHERE id=?", (phone_id,)).fetchone()
        if row and row[0] != owner:
            raise ValueError("This phone is registered to another account")
        self.db.execute("INSERT INTO phones VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name", (phone_id, owner, name, "[]"))
        self.db.commit()

    def actions(self, phone_id, actions):
        self.db.execute("UPDATE phones SET actions=? WHERE id=?", (json.dumps(actions), phone_id))
        self.db.commit()

    def phones(self, owner=None):
        rows = self.db.execute("SELECT id,owner,name,actions FROM phones" + (" WHERE owner=?" if owner else ""), (owner,) if owner else ())
        return [{"id": r[0], "owner": r[1], "name": r[2], "actions": json.loads(r[3])} for r in rows]

    def issue(self, kind, owner, ttl, phone="", grant=""):
        value = secrets.token_urlsafe(32)
        self.db.execute("INSERT INTO tokens VALUES(?,?,?,?,?,?)", (digest(value), kind, owner, phone, grant, self.clock()+ttl))
        self.db.commit()
        return value

    def token(self, value, kind):
        row = self.db.execute("SELECT owner,phone,grant_id FROM tokens WHERE hash=? AND kind=? AND expires>?", (digest(value), kind, self.clock())).fetchone()
        return {"owner": row[0], "phone": row[1], "grant": row[2]} if row else None

    def code(self, owner, redirect):
        value = secrets.token_urlsafe(32)
        self.db.execute("INSERT INTO codes VALUES(?,?,?,?)", (digest(value), owner, redirect, self.clock()+60))
        self.db.commit()
        return value

    def consume_code(self, value, redirect):
        with self.db:
            row = self.db.execute("SELECT owner FROM codes WHERE hash=? AND redirect=? AND expires>?", (digest(value), redirect, self.clock())).fetchone()
            if row:
                self.db.execute("DELETE FROM codes WHERE hash=?", (digest(value),))
        return row[0] if row else None

    def revoke_grant(self, grant):
        self.db.execute("DELETE FROM tokens WHERE grant_id=? AND kind IN ('access','refresh')", (grant,))
        self.db.commit()

    def execution(self, owner, request, body):
        row = self.db.execute("SELECT body_hash,response FROM executions WHERE owner=? AND request=? AND expires>?", (owner, request, self.clock())).fetchone()
        if row:
            if row[0] != digest(body):
                raise ValueError("Request ID was reused with different commands")
            return json.loads(row[1]) if row[1] else {"requestId": request, "payload": {"errorCode": "deviceBusy"}}
        self.db.execute("DELETE FROM executions WHERE owner=? AND request=?", (owner, request))
        self.db.execute("INSERT INTO executions VALUES(?,?,?,?,?)", (owner, request, digest(body), "", self.clock()+86400))
        self.db.execute("DELETE FROM executions WHERE expires<?", (self.clock(),))
        self.db.execute("DELETE FROM tokens WHERE expires<?", (self.clock(),))
        self.db.execute("DELETE FROM codes WHERE expires<?", (self.clock(),))
        self.db.commit()
        return None

    def finish_execution(self, owner, request, response):
        self.db.execute("UPDATE executions SET response=? WHERE owner=? AND request=?", (json.dumps(response), owner, request))
        self.db.commit()

    def close(self):
        self.db.close()
