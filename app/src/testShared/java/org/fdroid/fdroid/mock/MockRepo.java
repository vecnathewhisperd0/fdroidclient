package org.belos.belmarket.mock;

import org.belos.belmarket.data.Repo;

public class MockRepo extends Repo {

    public MockRepo(long id) {
        this.id = id;
    }

    public MockRepo(long id, int pushRequests) {
        this.id = id;
        this.pushRequests = pushRequests;
    }

}
