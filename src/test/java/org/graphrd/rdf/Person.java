package org.graphrd.rdf;

import java.util.List;

@RdfType("http://ex.org/Person")
class Person {

    @RdfId
    String id;

    @RdfProperty("http://ex.org/name")
    String name;

    @RdfProperty("http://ex.org/age")
    Integer age;

    @RdfProperty("http://ex.org/salary")
    Integer salary;

    /** Multi-valued: every nickname becomes its own triple. */
    @RdfProperty("http://ex.org/nick")
    List<String> nicks;

    @RdfProperty("http://ex.org/worksFor")
    Company worksFor;

    Person() {
    }

    Person(String id, String name, Integer age, Integer salary, Company worksFor, List<String> nicks) {
        this.id = id;
        this.name = name;
        this.age = age;
        this.salary = salary;
        this.worksFor = worksFor;
        this.nicks = nicks;
    }
}
