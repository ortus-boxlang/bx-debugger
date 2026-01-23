Add a test for ${file}

Check to see if there is a corresponding test file `src/test/java/ortus/boxlang/bxdebugger/boxlangIntegration/${fileBasenameNoExtension}.java`. If it does not exist then create it. This is the file where the tests will be implemented.

Read the contents of ${file}. The comments in that file will instruct you on what kind of test to create.

Focus only on creating the test. Ask for any clarifcation from the user if you need to.

The test should initialize the BoxLang debugger and run the BoxLang code in the corresponding `.bxs` file. The test should verify whatever the comments in the `.bxs` file indicate should be verified. For example, if the comment indicates that the output should be a certain value, then the test should verify that the output matches that value. Likewise, if the comment indicates that a breakpoint should be hit, then the test should verify that the breakpoint was hit and that the stack information is correct.

Once the test is complete ask the user to review it. If the user is satisfied ask them if they want you to commit the test.

