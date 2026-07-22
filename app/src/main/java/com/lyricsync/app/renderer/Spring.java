package com.lyricsync.app.renderer;

public class Spring {
    public double velocity;
    public double dampingRatio;
    public double frequency;
    public boolean sleeping;
    public double position;
    public double finalPosition;

    public Spring(double initial, double dampingRatio, double frequency) {
        if (dampingRatio * frequency < 0) {
            throw new IllegalArgumentException("Spring does not converge");
        }
        this.dampingRatio = dampingRatio;
        this.frequency = frequency;
        this.velocity = 0;
        this.position = initial;
        this.finalPosition = initial;
    }



    public double update(double deltaTime) {
        double radialFrequency = this.frequency * Math.PI * 2.0;
        double finalPos = this.finalPosition;
        double vel = this.velocity;
        double offset = this.position - finalPos;
        double dr = this.dampingRatio;
        double decay = Math.exp(-dr * radialFrequency * deltaTime);

        double newPosition;
        double newVelocity;

        if (Math.abs(dr - 1) < 1e-6) {
            newPosition = (offset * (1 + radialFrequency * deltaTime) + vel * deltaTime) * decay + finalPos;
            newVelocity = (vel * (1 - radialFrequency * deltaTime) - offset * (radialFrequency * radialFrequency * deltaTime)) * decay;
        } else if (dr < 1) {
            double c = Math.sqrt(1 - dr * dr);
            double i = Math.cos(radialFrequency * c * deltaTime);
            double j = Math.sin(radialFrequency * c * deltaTime);

            double z;
            if (c > 1e-4) z = j / c;
            else {
                double a = deltaTime * radialFrequency;
                z = a + ((a * a * c * c * c * c / 20 - c * c) * (a * a * a)) / 6;
            }

            double y;
            if (radialFrequency * c > 1e-4) y = j / (radialFrequency * c);
            else {
                double b = radialFrequency * c;
                y = deltaTime + ((deltaTime * deltaTime * b * b * b * b / 20 - b * b) * (deltaTime * deltaTime * deltaTime)) / 6;
            }

            newPosition = (offset * (i + dr * z) + vel * y) * decay + finalPos;
            newVelocity = (vel * (i - z * dr) - offset * (z * radialFrequency)) * decay;
        } else {
            double c = Math.sqrt(dr * dr - 1);
            double r1 = -radialFrequency * (dr - c);
            double r2 = -radialFrequency * (dr + c);
            double co2 = (vel - offset * r1) / (2 * radialFrequency * c);
            double co1 = offset - co2;
            double e1 = co1 * Math.exp(r1 * deltaTime);
            double e2 = co2 * Math.exp(r2 * deltaTime);
            newPosition = e1 + e2 + finalPos;
            newVelocity = e1 * r1 + e2 * r2;
        }

        this.position = newPosition;
        this.velocity = newVelocity;
        this.sleeping = Math.abs(finalPos - newPosition) <= 0.3 && Math.abs(newVelocity) < 1.0;
        return newPosition;
    }

    public void set(double value) {
        this.position = value;
        this.finalPosition = value;
        this.velocity = 0;
        this.sleeping = true;
    }
}
