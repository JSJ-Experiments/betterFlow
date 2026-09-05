#!/system/bin/sh
MODDIR=${0%/*}
echo "betterFlow hot update"
echo "====================="
MODDIR="$MODDIR" sh "$MODDIR/scripts/hot-update.sh"
