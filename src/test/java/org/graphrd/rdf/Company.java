package org.graphrd.rdf;

@RdfType("http://ex.org/Company")
class Company {

    @RdfId
    String id;

    @RdfProperty("http://ex.org/name")
    String name;

    Company() {
    }

    Company(String id, String name) {
        this.id = id;
        this.name = name;
    }
}
