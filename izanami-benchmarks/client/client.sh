export GOPATH=/gopath
export PATH=$PATH:/usr/local/go/bin:/gopath/bin

echo "Sleeping"
sleep 20
echo "Warm up ..."
wrk -R 500 -t6 -c200 -d40s -H "Izanami-Client-Id: apikey" -H "Izanami-Client-Secret: 123456" http://izanami:8080/api/features
echo "Warm up done !"
sleep 10
echo "Bench ..."
wrk -R 1000 -t20 -c500 -d40s -H "Izanami-Client-Id: apikey" -H "Izanami-Client-Secret: 123456" --latency http://izanami:8080/api/features

echo "Bench 2 ..."



#echo "Hey ..."
#hey -H "Izanami-Client-Id: apikey" -H "Izanami-Client-Secret: 123456" -n 1000 -c 400 -m GET http://izanami:8080/api/features