component {
    function run(required string label) {
        var payload = { label: arguments.label };
        for (var i = 1; i <= 3; i++) {
            systemOutput(arguments.label); // breakpoint
        }
    }
}
