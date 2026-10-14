Always use git to rollback file updates.
Always execute test after your changes in scala with command sbt 'testOnly *<FILE> -- -z "<TESTNAME>"' where TESTNAME is scala test name and <FILE> the file (without.scala extension).
Make sure the code you produce compile using sbt compile.
Edit only tests folder when I ask about writing test.
Always focus on running impacted tests rather than all tests, this would be too slow.
Try to use already present dependencies, always ask me before adding new ones.
When writting tests, avoid as much as possible SQL calls.
Ask before adding any test helper, prefer using helpers present in BaseAPISpec rather than create new one.
To launch all backend tests, use command sbt 'testOnly *AllTests'