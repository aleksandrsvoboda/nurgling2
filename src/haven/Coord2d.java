/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.util.Iterator;
import static java.lang.Math.PI;
import static java.lang.Math.atan;

public class Coord2d implements Comparable<Coord2d>, java.io.Serializable {
    public double x, y;
    public static final Coord2d z = new Coord2d(0, 0);

    public Coord2d(double x, double y) {
	this.x = x;
	this.y = y;
    }

    public Coord2d(Coord c) {
	this(c.x, c.y);
    }

    public Coord2d(Coord3f c) {
	this(c.x, c.y);
    }

    public Coord2d() {
	this(0, 0);
    }

    public static Coord2d of(double x, double y) {return(new Coord2d(x, y));}
    public static Coord2d of(double x) {return(of(x, x));}
    public static Coord2d of(Coord c) {return(of(c.x, c.y));}
    public static Coord2d of(Coord3f c) {return(of(c.x, c.y));}

    public boolean equals(double X, double Y) {
	return((x == X) && (y == Y));
    }

    public boolean equals(Object o) {
	if(!(o instanceof Coord2d))
	    return(false);
	Coord2d c = (Coord2d)o;
	return(equals(c.x, c.y));
    }

    public int hashCode() {
	long X = Double.doubleToLongBits(x);
	long Y = Double.doubleToLongBits(y);
	return((((int)(X ^ (X >>> 32))) * 31) + ((int)(Y ^ (Y >>> 32))));
    }

    public int compareTo(Coord2d c) {
	if(c.y < y) return(-1);
	if(c.y > y) return(1);
    return Double.compare(c.x, x);
    }

    public Coord2d add(double X, double Y) {
	return(of(x + X, y + Y));
    }

    public Coord2d add(Coord2d b) {
	return(add(b.x, b.y));
    }

    public Coord2d inv() {
	return(of(-x, -y));
    }

    public Coord2d sub(double X, double Y) {
	return(of(x - X, y - Y));
    }

    public Coord2d sub(Coord2d b) {
	return(sub(b.x, b.y));
    }

    public Coord2d mul(double f) {
	return(of(x * f, y * f));
    }

    public Coord2d mul(double X, double Y) {
	return(of(x * X, y * Y));
    }

    public Coord2d mul(Coord2d b) {
	return(mul(b.x, b.y));
    }

    public Coord2d div(double f) {
	return(of(x / f, y / f));
    }

    public Coord2d div(double X, double Y) {
	return(of(x / X, y / Y));
    }

    public Coord2d div(Coord2d b) {
	return(div(b.x, b.y));
    }

    public Coord round() {
	return(Coord.of((int)Math.round(x), (int)Math.round(y)));
    }
    public Coord2d roundf() {
	return(Coord2d.of(Math.round(x), Math.round(y)));
    }

    public Coord floor() {
	return(Coord.of((int)Math.floor(x), (int)Math.floor(y)));
    }

    public Coord ceil() {
        return(Coord.of((int)Math.ceil(x), (int)Math.ceil(y)));
    }
    public Coord2d floorf() {
	return(Coord2d.of(Math.floor(x), Math.floor(y)));
    }

    public Coord floor(double X, double Y) {
	return(Coord.of((int)Math.floor(x / X), (int)Math.floor(y / Y)));
    }

    public Coord floor(Coord2d f) {
	return(floor(f.x, f.y));
    }

    public Coord2d mod() {
	return(of(x - Math.floor(x), y - Math.floor(y)));
    }

    public Coord2d mod(double X, double Y) {
	return(of(x - (Math.floor(x / X) * X), y - (Math.floor(y / Y) * Y)));
    }

    public Coord2d mod(Coord2d f) {
	return(mod(f.x, f.y));
    }

    public double angle(Coord2d o) {
	return(Math.atan2(o.y - y, o.x - x));
    }

    public double dist(Coord2d o) {
	return(Math.hypot(x - o.x, y - o.y));
    }

    public double abs() {
	return(Math.hypot(x, y));
    }

    public Coord2d norm(double n) {
	return(mul(n / abs()));
    }

    public Coord2d norm() {
	return(norm(1.0));
    }

    public Coord2d rot(double a) {
	double s = Math.sin(a), c = Math.cos(a);
	return(of((x * c) - (y * s), (y * c) + (x * s)));
    }

    public static Coord2d sc(double a, double r) {
	return(of(Math.cos(a) * r, Math.sin(a) * r));
    }

    public String toString() {
	return("(" + x + ", " + y + ")");
    }

    public Coord2d rotate(double angle){
        return new Coord2d ( x*Math.cos ( angle ) - y*Math.sin(angle), x*Math.sin ( angle ) + y*Math.cos(angle));
    }

    public Coord2d shift(Coord2d other){
        return new Coord2d ( x + other.x, y + other.y);
    }

    public double dot(Coord2d other){
        return  ( x * other.x + y * other.y);
    }

    public double len(){
        return Math.sqrt ( x*x+ y*y );
    }

    public double proj (Coord2d other){
        return dot( other )/len ();
    }

    public double curAngle() {
        double res = 0;
        if (x > 0 && y >= 0) {
            res = atan(y / x);
        } else if (x > 0 && y < 0) {
            res = atan(y / x) + 2 * PI;
        } else if (x < 0) {
            res = atan(y / x) + PI;
        } else if (x == 0 && y > 0) {
            res = PI / 2;
        } else if (x == 0 && y < 0) {
            res = 3 * PI / 2;
        }
        return res;
    }

    public boolean isect(Coord2d ul, Coord2d br) {
        return ((x >= ul.x) && (y >= ul.y) && (x < br.x) && (y < br.y));
    }

}
