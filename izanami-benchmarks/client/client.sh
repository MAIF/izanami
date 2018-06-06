echo "Sleeping"
sleep 10
echo "Warm up ..."
wrk -R 500 -t6 -c200 -d40s -H "Izanami-Client-Id: apikey" -H "Izanami-Client-Secret: 123456" http://izanami:8080/api/features
echo "Warm up done !"
sleep 10
echo "Bench ..."
wrk -R 2000 -200 -c800 -d40s -H "Izanami-Client-Id: apikey" -H "Izanami-Client-Secret: 123456" --latency http://izanami:8080/api/features