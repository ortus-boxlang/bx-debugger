component {
    function outer() {
        var marker = "caller";
        inner();
    }
    function inner() {
        var marker = "callee";
        var payload = { answer: 42, items: [ "one", "two" ] };
        systemOutput( marker ); // breakpoint
    }
}
