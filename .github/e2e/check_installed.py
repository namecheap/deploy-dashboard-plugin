"""Assert the packaged plugin actually loaded into the running Jenkins.

An .hpi that fails to load leaves Jenkins up and serving pages, so a green
start tells us nothing -- ask Jenkins what it thinks of the plugin instead.
"""
import json
import sys
import urllib.request

url = "http://localhost:8080/pluginManager/api/json?depth=1"
plugins = {p["shortName"]: p for p in json.load(urllib.request.urlopen(url))["plugins"]}

plugin = plugins.get("deploy-dashboard")
if plugin is None:
    sys.exit("deploy-dashboard is not installed at all")
if not plugin.get("active") or not plugin.get("enabled"):
    sys.exit("deploy-dashboard is installed but not active: {}".format(plugin))

print("deploy-dashboard {} active".format(plugin["version"]))
