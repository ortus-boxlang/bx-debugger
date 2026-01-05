The current code meets functional expectations. I want to take a pass at putting on the finishing touches before committing it and sending it out for review.

# Refactoring Guidelines

* Focus on recent changes ONLY - we can change existing code but we aren't trying to improve it.
* Follow DRY - look for changes that duplicate code/logic and can be consolidated into a method and reused
* Look for opportunities to improve documentation or clarity
* Prioritize creating focused self-documenting methods over verbose comments

Example of extracting a method vs using comments

```java

public void doStuff( Person p ){
    // lots of logic and code

    int age = p.getAge();
    int years = getYears();

    // add the years to age and update the person
    int newAge = age + years;

    // update the person object
    p.setAge( newAge );

    // more code...
}
```

The refcatored version
```java

public void doStuff( Person p ){
    // lots of logic and code

    p.setAge( calculateAge( p.getAge(), getYears() ) );

    // more code...
}

private int calculateAge( int age, int years ){
    return age + years;
}
```

# Task List

* Look for code that is unused and can be safely removed
* Look for repetitive code that can be refactored
* Look for logical pieces of code that can be refactored into small focused methods
* Look for any missing documentation

If any changes are found go ahead and implement them or ask for clarity. If everything looks good and there are no changes to be made that is okay too. Summarize your findings when done.



