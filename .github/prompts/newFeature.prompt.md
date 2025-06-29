Implement a new feature.

Before doing anything else run `git status` to check for uncommitted changes. If there are any, ask the user to commit or stash them before proceeding.

Follow the steps below:

1. Request the user to describe the feature they want to implement.
2. Consider the user's input and clarify any ambiguities.
   - Ask for specific details about the feature, such as its purpose, expected behavior, and any relevant context.
   - If the user provides a name for the feature, use it; otherwise, suggest a descriptive name based on their input.
   - Example: "You want to implement a feature that allows pausing on breakpoints. Let's call it `pause-on-breakpoint`."
3. Create a plan for implementing the feature, output a simple outline for the user to see.
   - Define the feature's purpose and functionality.
   - Identify any new commands or configuration options needed.
   - Determine how the feature will interact with existing components.
4. Implement the feature:
   - Create and checkout a new branch to work on: `feature/${input:name:feature-name-goes-here}`
   - Implement tests to cover the new functionality.
   - Use existing test frameworks and patterns in the codebase.
   - Commit changes incrementally with clear messages.
   - Ensure the feature adheres to the project's coding standards and style.
   - Provide documentation for the feature, including usage examples and configuration options.
5. Provide a brief summary of the completed feature to the user and ask for their feedback.
6. If the user is satisfied, ask the user if they want to merge the feature into the development branch.