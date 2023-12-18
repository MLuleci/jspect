package com.example;

public class Complex
{
    private final double a;
    private final double b;

    public Complex()
    { 
        this.a = 0;
        this.b = 0;
    }

    public Complex(double a)
    {
        this.a = a;
        this.b = 0;
    }

    public Complex(double a, double b)
    {
        this.a = a;
        this.b = b;
    }

    public Complex multiply(Complex c)
    {
        return new Complex(
            this.a * c.a - this.b * c.b,
            this.a * c.b + this.b * c.a
        );
    }

    public Complex add(Complex c)
    {
        return new Complex(
            this.a + c.a,
            this.b + c.b
        );
    }

    public Complex subtract(Complex c)
    {
        return new Complex(
            this.a - c.a,
            this.b - c.b
        );
    }

    public Complex divideBy(double d)
    {
        return new Complex(this.a / d, this.b / d);
    }

    public double abs()
    { 
        return Math.hypot(this.a, this.b);
    }

    public double arg()
    {
        return Math.atan2(this.b, this.a);
    }
}