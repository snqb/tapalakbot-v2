#!/bin/sh
echo "149.154.167.220 api.telegram.org" >> /etc/hosts
exec "$@"
