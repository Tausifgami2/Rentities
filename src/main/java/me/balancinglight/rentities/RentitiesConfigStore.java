package me.balancinglight.rentities;

public class RentitiesConfigStore {
    private final RentitiesConfig config;

    public RentitiesConfigStore() {
        config = Rentities.config;
    }

    public RentitiesConfig getData() {
        return config;
    }

    public void save() {
        config.save();
    }
}

