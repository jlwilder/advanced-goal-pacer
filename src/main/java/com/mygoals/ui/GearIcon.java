package com.mygoals.ui;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import javax.swing.Icon;

/** Vector icon so the header stays crisp with RuneLite's UI scaling. */
final class GearIcon implements Icon
{
	private static final Area SHAPE = shape();

	private static Area shape()
	{
		Area gear = new Area(new Ellipse2D.Double(3, 3, 10, 10));
		for (int tooth = 0; tooth < 8; tooth++)
		{
			gear.add(new Area(AffineTransform.getRotateInstance(tooth * Math.PI / 4, 8, 8)
					.createTransformedShape(new Rectangle2D.Double(6.5, 0.5, 3, 4))));
		}
		gear.subtract(new Area(new Ellipse2D.Double(5, 5, 6, 6)));
		return gear;
	}

	@Override
	public int getIconWidth()
	{
		return 16;
	}

	@Override
	public int getIconHeight()
	{
		return 16;
	}

	@Override
	public void paintIcon(Component component, Graphics graphics, int x, int y)
	{
		Graphics2D copy = (Graphics2D) graphics.create();
		try
		{
			copy.translate(x, y);
			copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			copy.setColor(component.getForeground());
			copy.fill(SHAPE);
		}
		finally
		{
			copy.dispose();
		}
	}
}
