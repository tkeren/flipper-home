import argparse
import getpass
import os
from pathlib import Path

from aiohttp import web
from .server import Settings, create_app
from .storage import Store

parser = argparse.ArgumentParser(description="Flipper Home direct Google bridge")
parser.add_argument("command", choices=["serve", "create-user"])
parser.add_argument("username", nargs="?")
args = parser.parse_args()
database = os.environ.get("BRIDGE_DATABASE", "data/bridge.sqlite3")
if args.command == "create-user":
    Path(database).parent.mkdir(parents=True, exist_ok=True)
    store = Store(database)
    try:
        password = getpass.getpass("Bridge password (12+ characters): ")
        if password != getpass.getpass("Confirm password: "):
            raise ValueError("Passwords do not match")
        store.create_user(args.username or input("Username: "), password)
        print("Bridge account created")
    finally:
        store.close()
else:
    settings = Settings(os.environ["BRIDGE_PUBLIC_URL"], os.environ["GOOGLE_PROJECT_ID"],
        os.environ["GOOGLE_CLIENT_ID"], os.environ["GOOGLE_CLIENT_SECRET"], database)
    web.run_app(create_app(settings), host=os.environ.get("BRIDGE_BIND", "127.0.0.1"),
        port=int(os.environ.get("PORT", "8080")), access_log=None)
