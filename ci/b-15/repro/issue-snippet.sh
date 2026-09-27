./gradlew linkReleaseExecutableLinuxX64
./build/bin/linuxX64/releaseExecutable/*.kexe & server=$!
sleep 2
until=$(( $(date +%s) + 1200 )); loops=()
for c in $(seq 1 64); do
  ( while [ "$(date +%s)" -lt "$until" ]; do
      curl -s -o /dev/null -X POST http://127.0.0.1:18108/records --data-binary x
      echo $? ; sleep 0.02
    done > "rc.$c" ) & loops+=($!)
done
wait "${loops[@]}"; kill "$server"
cat rc.* | sort | uniq -c    # "52" lines are the empty replies
